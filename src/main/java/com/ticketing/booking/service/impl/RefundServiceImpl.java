package com.ticketing.booking.service.impl;

import com.ticketing.booking.service.*;
import com.ticketing.booking.repository.*;
import com.ticketing.booking.model.po.*;
import com.ticketing.booking.model.dto.*;
import com.ticketing.booking.model.vo.*;

import com.ticketing.booking.config.Diagnostics;
import com.ticketing.booking.config.RuntimeServices;
import com.ticketing.booking.domain.ApiFailure;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.ticketing.booking.domain.Values.*;

/** Full simulated refunds. Payment facts remain immutable; each order has one durable refund. */
@Service
public class RefundServiceImpl implements RefundService {
    private final RefundRepository repository;
    private final PaymentRepository payments;
    private final DatabaseTimeRepository clock;
    private final RuntimeServices runtime;
    private final OrderCloseService close;
    private final Diagnostics diagnostics;
    private final TransactionTemplate tx;
    private final boolean switchEnabled;

    public RefundServiceImpl(RefundRepository repository,PaymentRepository payments,DatabaseTimeRepository clock,RuntimeServices runtime,OrderCloseService close,Diagnostics diagnostics,
            PlatformTransactionManager manager,@Value("${biz.simulation.enabled:false}") boolean switchEnabled) {
        this.repository=repository;this.payments=payments;this.clock=clock;this.runtime=runtime;this.close=close;this.diagnostics=diagnostics;this.switchEnabled=switchEnabled;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(2);tx.setTimeout(8);
    }

    public boolean enabled() { return switchEnabled && runtime.productEnabled() && runtime.simulatorEnabled(); }
    private void requireEnabled() { if(!enabled()) { throw new ApiFailure(503,"REFUND_DISABLED"); } }

    public RefundAcceptedVO accept(RefundAcceptDTO request) {
        long user=request.userId(),orderId=request.orderId();String key=request.idempotencyKey(),suppliedReason=request.reason();
        requireEnabled();validateKey(key);String reason=reason(suppliedReason);
        close.query(user,orderId);
        var prior=existing(user,orderId,key,reason);
        if(prior!=null) { return receipt(prior); }
        try {
            runtime.at("R0_IDEMPOTENCY_MISS",orderId);
            long id=tx.execute(status -> {
                var locked=close.lockOrder(orderId,user);var order=locked.order();
                // Keep the common lock prefix, then payment attempt, then refund, then recovery audit.
                PaymentAttemptPO paid=order.get("paid_attempt_id")==null?null:
                    payments.lockAttempt(number(order,"paid_attempt_id"));
                var previous=repository.lockByKey(user,key);
                if(previous!=null) { checkKey(previous,orderId,reason);return previous.id(); }
                var current=repository.lockRefundIdByOrder(orderId);
                if(current!=null || order.get("current_refund_id")!=null) { throw new ApiFailure(409,"REFUND_ALREADY_REQUESTED"); }
                runtime.at("R0_LOCKS_ACQUIRED",orderId);
                Instant now=runtime.afterLocks(clock);
                if(!"PAID".equals(order.get("status"))) { throw new ApiFailure(409,"ORDER_NOT_REFUNDABLE"); }
                // Session was already SHARE locked before any inventory/order lock; published start is immutable.
                var session=repository.sessionStart(number(order,"session_id"));
                if(!now.isBefore(session)) { throw new ApiFailure(409,"REFUND_WINDOW_CLOSED"); }
                assertPaid(locked,paid);assertActive(locked);
                long refund=repository.createRefund(orderId,user,number(order,"session_id"),paid.id(),key,number(order,"payable_cent"),(String)order.get("currency"),reason,now);
                runtime.at("R0_AFTER_REQUEST",refund);
                repository.markOrderRefunding(refund,orderId,user);
                runtime.at("R0_AFTER_ORDER",refund);
                runtime.at("R0_BEFORE_COMMIT",refund);
                return refund;
            });
            runtime.at("R0_COMMITTED",id);
            runtime.at("BEFORE_REFUND_RESPONSE",id);
            return receipt(repository.requireRefund(id));
        } catch(ApiFailure expected) { throw expected; }
        catch(DuplicateKeyException duplicate) {
            var visible=existing(user,orderId,key,reason);if(visible!=null) { return receipt(visible); }
            throw new ApiFailure(503,"REFUND_ACCEPTANCE_UNKNOWN").causedBy(duplicate);
        } catch(RuntimeException uncertain) {
            throw new ApiFailure(503,"REFUND_ACCEPTANCE_UNKNOWN").causedBy(uncertain);
        }
    }

    public RefundVO query(long user,long id) {
        requireEnabled();
        var row=repository.findForUser(user,id);
        if(row==null) { throw new ApiFailure(404,"RESOURCE_NOT_FOUND"); }
        return view(row);
    }

    /** Controller performs requireAdmin before this cross-owner diagnostic query. */
    public RefundVO queryForAdmin(long id) {
        requireEnabled();
        var row=repository.findForAdmin(id);
        if(row==null) { throw new ApiFailure(404,"RESOURCE_NOT_FOUND"); }
        return view(row);
    }

    public PageVO<RefundVO> listForAdmin(String status,int page,int size) {
        requireEnabled();
        if(!java.util.Set.of("PENDING","SUCCEEDED","REVIEW").contains(status) || page<1 || page>10000 || size<1 || size>50) {
            throw new ApiFailure(400,"INVALID_ARGUMENT");
        }
        var rows=repository.listForAdmin(status,page,size);
        return new PageVO<>(rows.stream().limit(size).map(RefundServiceImpl::view).toList(),page,size,rows.size()>size);
    }

    private record Generation(long round,int attempts,Instant due) {
        boolean matches(RefundRequestPO row) {
            return "PENDING".equals(row.status()) && row.recoveryGeneration()==round
                && row.attempts()==attempts && row.nextAttemptAt()!=null
                && row.nextAttemptAt().equals(due);
        }
    }

    /** One atomic success: refund fact + order close + stock + exact quota owner + unique ledger release. */
    public void settle(long id) {
        if(!enabled()) { return; }
        Generation observed=null;
        try {
            var candidate=repository.observePending(id);
            if(candidate==null) { return; }
            observed=new Generation(candidate.recoveryGeneration(),candidate.attempts(),candidate.nextAttemptAt());
            final Generation generation=observed;
            runtime.at("R1_OBSERVED",id);
            tx.executeWithoutResult(status -> {
                var locked=lockRoute(id);var order=locked.order();
                var paid=lockPayment(order);
                var refund=repository.lockRefund(id);
                // A stale worker must never adopt a retry generation created while it waited for locks.
                if(!generation.matches(refund)) { return; }
                runtime.at("R1_LOCKS_ACQUIRED",id);
                Instant now=runtime.afterLocks(clock);
                if(now.isBefore(generation.due())) { return; }
                assertRefund(locked,paid,refund);assertActive(locked);
                long orderId=number(order,"id"),tier=number(order,"tier_id"),user=number(order,"user_id"),session=number(order,"session_id");
                repository.succeed(id,generation.round(),now);
                runtime.at("R1_AFTER_REFUND",id);
                repository.markOrderRefunded(orderId,id,now);
                runtime.at("R1_AFTER_ORDER",id);
                repository.releaseStock(tier);
                runtime.at("R1_AFTER_STOCK",id);
                repository.releaseQuota(user,session,orderId,now);
                runtime.at("R1_AFTER_QUOTA",id);
                repository.recordRelease(tier,orderId,number(locked.inventory(),"available")+1,now);
                runtime.at("R1_AFTER_LEDGER",id);
                runtime.at("R1_BEFORE_COMMIT",id);
            });
            runtime.at("R1_COMMITTED",id);
        } catch(RuntimeException failure) {
            diagnostics.record(new IllegalStateException("REFUND_SETTLEMENT_FAILED:"+id,failure));
            if(observed!=null) {
                try { recordFailure(id,observed); } catch(RuntimeException persistenceFailure) { diagnostics.record(persistenceFailure); }
            }
        }
    }

    public void scanOnce() {
        if(!enabled()) { return; }
        try {
            var ids=repository.dueRefunds();
            for(long id:ids) { settle(id); }
        } catch(RuntimeException failure) { diagnostics.record(failure); }
    }

    private void recordFailure(long id,Generation observed) {
        runtime.at("R1_BEFORE_FAILURE_RECORD",id);
        tx.executeWithoutResult(status -> {
            var locked=lockRoute(id);lockPayment(locked.order());
            var refund=repository.lockRefund(id);
            if(!observed.matches(refund)) { return; }
            Instant now=runtime.afterLocks(clock);
            if(now.isBefore(observed.due())) { return; }
            int count=observed.attempts()+1;
            if(count>=6) {
                repository.exhaustRetries(id,observed.round(),count);
            } else {
                long delay=(1L<<(count-1))*1000;delay+=ThreadLocalRandom.current().nextLong(delay/5+1);
                repository.scheduleRetry(id,observed.round(),count,now.plusMillis(delay));
            }
        });
    }

    /** A technical recovery keeps the refund ID and the original requested time/amount/payment untouched. */
    public RefundAcceptedVO retry(RefundRetryDTO request) {
        long operator=request.operatorId(),id=request.refundId();String key=request.idempotencyKey(),suppliedReason=request.reason();
        requireEnabled();validateKey(key);String reason=reason(suppliedReason);
        boolean exists=repository.refundExists(id);
        if(!exists) { throw new ApiFailure(404,"RESOURCE_NOT_FOUND"); }
        var prior=existingRetry(operator,id,key,reason);
        if(prior!=null) { return receipt(repository.requireRefund(id)); }
        try {
            tx.executeWithoutResult(status -> {
                var locked=lockRoute(id);var paid=lockPayment(locked.order());
                var refund=repository.lockRefund(id);
                var previous=repository.lockRetryByKey(operator,key);
                if(previous!=null) { checkRetry(previous,id,reason);return; }
                if(!"REVIEW".equals(refund.status())) { throw new ApiFailure(409,"REFUND_NOT_RECOVERABLE"); }
                try { assertRefund(locked,paid,refund);assertActive(locked); }
                catch(IllegalStateException inconsistent) { throw new ApiFailure(409,"REFUND_RECOVERY_INCONSISTENT").causedBy(inconsistent); }
                runtime.at("R2_LOCKS_ACQUIRED",id);
                Instant now=runtime.afterLocks(clock);
                long generation=Math.addExact(refund.recoveryGeneration(),1);
                repository.reopen(id,refund.recoveryGeneration(),generation,now);
                repository.recordRecovery(id,operator,key,reason,generation,now);
                runtime.at("R2_AFTER_AUDIT",id);
                runtime.at("R2_BEFORE_COMMIT",id);
            });
            runtime.at("R2_COMMITTED",id);
            return receipt(repository.requireRefund(id));
        } catch(ApiFailure expected) { throw expected; }
        catch(DuplicateKeyException duplicate) {
            var visible=existingRetry(operator,id,key,reason);
            if(visible!=null) { return receipt(repository.requireRefund(id)); }
            throw new ApiFailure(503,"REFUND_RECOVERY_UNKNOWN").causedBy(duplicate);
        } catch(RuntimeException uncertain) { throw new ApiFailure(503,"REFUND_RECOVERY_UNKNOWN").causedBy(uncertain); }
    }

    private OrderCloseService.LockedOrder lockRoute(long id) {
        return close.lockOrder(repository.orderId(id),null);
    }
    private PaymentAttemptPO lockPayment(Map<String,Object> order) {
        if(order.get("paid_attempt_id")==null) { throw new IllegalStateException("REFUND_PAYMENT_MISSING"); }
        return payments.lockAttempt(number(order,"paid_attempt_id"));
    }
    private void assertPaid(OrderCloseService.LockedOrder locked,PaymentAttemptPO paid) {
        var order=locked.order();
        if(paid==null || order.get("paid_attempt_id")==null || order.get("current_attempt_id")==null || order.get("paid_at")==null
            || paid.id()!=number(order,"paid_attempt_id") || paid.id()!=number(order,"current_attempt_id")
            || paid.orderId()!=number(order,"id") || paid.userId()!=number(order,"user_id")
            || paid.sessionId()!=number(order,"session_id") || paid.amountCent()!=number(order,"payable_cent")
            || !Objects.equals(paid.currency(),order.get("currency"))
            || !"SUCCESS".equals(paid.resultStatus()) || !"APPLIED".equals(paid.settlementStatus())
            || paid.resultDigest()==null || !MessageDigest.isEqual(PaymentServiceImpl.digest(paid),paid.resultDigest())) {
            throw new IllegalStateException("REFUND_PAYMENT_INCONSISTENT");
        }
    }
    private void assertRefund(OrderCloseService.LockedOrder locked,PaymentAttemptPO paid,RefundRequestPO refund) {
        var order=locked.order();assertPaid(locked,paid);
        if(!"REFUNDING".equals(order.get("status")) || order.get("current_refund_id")==null
            || number(order,"current_refund_id")!=refund.id() || number(order,"id")!=refund.orderId()
            || number(order,"user_id")!=refund.userId() || number(order,"session_id")!=refund.sessionId()
            || paid.id()!=refund.paymentAttemptId() || paid.amountCent()!=refund.amountCent()
            || !Objects.equals(paid.currency(),refund.currency())) {
            throw new IllegalStateException("REFUND_ASSOCIATION_INCONSISTENT");
        }
    }
    private void assertActive(OrderCloseService.LockedOrder locked) {
        var order=locked.order();var quota=locked.quota();long id=number(order,"id");
        if(!OrderCloseService.validSource(locked) || quota==null || !"ORDER".equals(quota.get("owner_type")) || number(quota,"owner_id")!=id) {
            throw new IllegalStateException("REFUND_OWNER_INCONSISTENT");
        }
        var ledger=payments.reservationLedger(id);
        if(ledger.size()!=1 || !"RESERVE".equals(ledger.get(0).action()) || ledger.get(0).delta()!=-1
            || ledger.get(0).tierId()!=number(order,"tier_id")) { throw new IllegalStateException("REFUND_LEDGER_INCONSISTENT"); }
    }
    private RefundRequestPO existing(long user,long order,String key,String reason) {
        var row=repository.findByKey(user,key);
        if(row!=null) { checkKey(row,order,reason); }return row;
    }
    private RefundRetryPO existingRetry(long operator,long id,String key,String reason) {
        var row=repository.findRetryByKey(operator,key);
        if(row!=null) { checkRetry(row,id,reason); }return row;
    }
    private static void checkKey(RefundRequestPO row,long order,String reason) {
        if(row.orderId()!=order || !reason.equals(row.reason())) { throw new ApiFailure(409,"IDEMPOTENCY_CONFLICT"); }
    }
    private static void checkRetry(RefundRetryPO row,long id,String reason) {
        if(row.refundId()!=id || !reason.equals(row.reason())) { throw new ApiFailure(409,"IDEMPOTENCY_CONFLICT"); }
    }
    private static void validateKey(String key) {
        if(key==null || !key.matches("[A-Za-z0-9._:-]{8,64}")) { throw new ApiFailure(400,"INVALID_ARGUMENT"); }
    }
    private static String reason(String value) {
        if(value==null) { throw new ApiFailure(400,"INVALID_ARGUMENT"); }
        String normalized=value.trim();
        if(normalized.isEmpty() || normalized.length()>200 || normalized.chars().anyMatch(Character::isISOControl)) { throw new ApiFailure(400,"INVALID_ARGUMENT"); }
        return normalized;
    }
    private static RefundAcceptedVO receipt(RefundRequestPO row) {
        return new RefundAcceptedVO(Long.toString(row.id()),Long.toString(row.orderId()),"/v1/refunds/"+row.id(),row.status(),true);
    }
    private static RefundVO view(RefundQueryPO projection) {
        var row=projection.refund();
        return new RefundVO(Long.toString(row.id()),Long.toString(row.orderId()),"/v1/refunds/"+row.id(),row.status(),true,
            row.amountCent(),row.currency(),row.reason(),projection.orderStatus(),row.reviewReason(),row.attempts(),row.recoveryGeneration(),row.lastErrorCode(),
            time(row.requestedAt()),row.completedAt()==null?null:time(row.completedAt()));
    }
}
