package com.ticketing.booking.service.impl;

import com.ticketing.booking.service.*;
import com.ticketing.booking.repository.*;
import com.ticketing.booking.model.po.*;
import com.ticketing.booking.model.dto.*;
import com.ticketing.booking.model.vo.*;

import com.ticketing.booking.config.*;
import com.ticketing.booking.domain.ApiFailure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.ticketing.booking.domain.Values.*;

/** P0 acceptance, P1 immutable simulation result, P2 settlement. No real payment channel. */
@Service
public class PaymentServiceImpl implements PaymentService {
    private final PaymentRepository repository;
    private final DatabaseTimeRepository clock;
    private final RuntimeServices runtime;
    private final OrderCloseService close;
    private final Diagnostics diagnostics;
    private final TransactionTemplate tx;
    private final boolean switchEnabled;
    public PaymentServiceImpl(PaymentRepository repository,DatabaseTimeRepository clock,RuntimeServices runtime,OrderCloseService close,Diagnostics diagnostics,
            PlatformTransactionManager manager,@Value("${biz.simulation.enabled:false}") boolean switchEnabled) {
        this.repository=repository;this.clock=clock;this.runtime=runtime;this.close=close;this.diagnostics=diagnostics;this.switchEnabled=switchEnabled;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(2);tx.setTimeout(8);
    }
    public boolean enabled() { return switchEnabled && runtime.simulatorEnabled(); }
    public PaymentAcceptedVO accept(PaymentAcceptDTO request) {
        long user=request.userId(),order=request.orderId();String key=request.idempotencyKey();
        if(key==null || !key.matches("[A-Za-z0-9._:-]{8,64}")) { throw new ApiFailure(400,"INVALID_ARGUMENT"); }
        close.query(user,order);
        if(!enabled()) { throw new ApiFailure(503,"SIMULATOR_DISABLED"); }
        var prior=existing(user,order,key); if(prior!=null) { return envelope(prior); }
        try {
            runtime.at("P0_IDEMPOTENCY_MISS",order);
            Long id=tx.execute(status->{
                var locked=close.lockOrder(order,user); var o=locked.order();
                // Repeat after order serialization: original key remains valid after any order terminal state.
                var previous=repository.lockByKey(user,key);
                if(previous!=null) { checkKeyOrder(previous,order);return previous.id(); }
                PaymentAttemptPO current=null;
                if(o.get("current_attempt_id")!=null) {
                    current=repository.lockAttempt(number(o,"current_attempt_id"));
                    association(current,o);
                }
                runtime.at("P0_LOCKS_ACQUIRED",order);
                Instant now=runtime.afterLocks(clock);
                if(!"HOLD".equals(o.get("status"))) { throw new ApiFailure(409,"ORDER_NOT_PAYABLE"); }
                if(!now.isBefore(instant(o.get("pay_expires_at")))) {
                    close.closeLocked(locked,now,false);return null; // Commit C before translating to HTTP conflict.
                }
                assertActive(locked);
                if(current!=null && !("FAILED".equals(current.resultStatus()) && "NO_CHARGE".equals(current.settlementStatus()))) {
                    throw new ApiFailure(409,"PAYMENT_IN_PROGRESS");
                }
                var plan=runtime.simulationPlan();
                long attempt=repository.createAttempt(order,user,number(o,"session_id"),key,current==null?1:(long)current.attemptNo()+1,number(o,"payable_cent"),(String)o.get("currency"),now,plan.result(),now.plusMillis(plan.delayMillis()),now.plusSeconds(1800));
                runtime.at("P0_AFTER_ATTEMPT",attempt);
                repository.attachCurrentAttempt(attempt,order,user);
                runtime.at("P0_BEFORE_COMMIT",attempt);
                return attempt;
            });
            if(id==null) { throw new ApiFailure(409,"PAYMENT_DEADLINE_EXCEEDED"); }
            runtime.at("P0_COMMITTED",id);
            if(runtime.directEnabled()) { generate(id);settle(id); }
            runtime.at("BEFORE_PAYMENT_RESPONSE",id);
            return envelope(repository.requireAttempt(id));
        } catch(ApiFailure expected) { throw expected; }
        catch(org.springframework.dao.DuplicateKeyException duplicate) {
            var visible=existing(user,order,key);if(visible!=null) { return envelope(visible); }
            throw new ApiFailure(503,"PAYMENT_ACCEPTANCE_UNKNOWN").causedBy(duplicate);
        } catch(RuntimeException unknown) { throw new ApiFailure(503,"PAYMENT_ACCEPTANCE_UNKNOWN").causedBy(unknown); }
    }
    private PaymentAttemptPO existing(long user,long order,String key) {
        var a=repository.findByKey(user,key);
        if(a!=null) { checkKeyOrder(a,order); }return a;
    }
    private static void checkKeyOrder(PaymentAttemptPO a,long order) {
        if(a.orderId()!=order) { throw new ApiFailure(409,"IDEMPOTENCY_CONFLICT"); }
    }
    private static PaymentAcceptedVO envelope(PaymentAttemptPO a) {
        return new PaymentAcceptedVO(Long.toString(a.id()),Long.toString(a.orderId()),
            "/v1/payment-attempts/"+a.id(),true,"模拟支付，无真实资金");
    }
    public PaymentVO query(long user,long id) {
        var row=repository.findForUser(id,user,runtime.productEnabled());
        if(row==null) { throw new ApiFailure(404,"RESOURCE_NOT_FOUND"); }
        var a=row.attempt();
        var refunds=row.refundFields()?new PaymentVO.RefundLinks(id(row.currentRefundId()),timeOrNull(row.refundedAt()),timeOrNull(row.refundDeadline())):null;
        return new PaymentVO(Long.toString(a.id()),Long.toString(a.orderId()),"/v1/payment-attempts/"+a.id(),true,"模拟支付，无真实资金",
            a.resultStatus(),a.settlementStatus(),a.amountCent(),a.currency(),row.orderStatus(),a.resultId(),a.resultReason(),a.reviewReason(),
            a.compensationId(),a.compensationCent(),time(a.createdAt()),time(a.resolutionDeadline()),timeOrNull(a.resultAt()),timeOrNull(a.settledAt()),
            timeOrNull(a.compensationAt()),id(row.currentAttemptId()),id(row.paidAttemptId()),timeOrNull(row.paidAt()),refunds);
    }
    private static String id(Long value) { return value==null?null:Long.toString(value); }
    private static String timeOrNull(Instant value) { return value==null?null:time(value); }
    public OrderCloseService.LockedOrder lockRoute(long attempt) {
        return close.lockOrder(repository.orderId(attempt),null);
    }
    public void generate(long id) {
        if(!enabled()) { return; }
        tx.executeWithoutResult(status->{
            var locked=lockRoute(id);var a=repository.lockAttempt(id);
            association(a,locked.order());
            if(!"PENDING".equals(a.resultStatus())) { return; }
            runtime.at("P1_LOCKS_ACQUIRED",id);
            Instant now=runtime.afterLocks(clock);
            boolean expired=!now.isBefore(a.resolutionDeadline());
            if(!expired && now.isBefore(a.plannedAt())) { return; }
            String result=expired?"FAILED":a.plannedStatus();
            String reason=expired?"SIM_GENERATION_EXPIRED":result.equals("SUCCESS")?"SIM_SUCCESS":"SIM_DECLINED";
            a=a.withResult(result,UUID.randomUUID().toString(),now,reason);
            repository.recordResult(a,digest(a));
            runtime.at("P1_AFTER_RESULT",id);
            repository.createResultEvent(id,a.resultId(),now);
            runtime.at("P1_BEFORE_COMMIT",id);
        });
        runtime.at("P1_COMMITTED",id);
    }
    public record Generation(long round,int attempts,Instant due) {
        public boolean matches(PaymentResultEventPO event) {
            return "READY".equals(event.state())
                && event.recoveryGeneration()==round
                && event.attempts()==attempts
                && event.nextAttemptAt()!=null
                && event.nextAttemptAt().equals(due);
        }
    }
    public void settle(long id) {
        final Generation[] generation={null};
        try {
            // Observe a generation without locks so even a later lock-timeout can be counted safely.
            var observed=repository.observeReadyEvent(id);
            if(observed==null) { return; }
            generation[0]=new Generation(observed.recoveryGeneration(),observed.attempts(),observed.nextAttemptAt());
            runtime.at("P2_OBSERVED",id);
            tx.executeWithoutResult(status->{
                var locked=lockRoute(id);var o=locked.order();
                var a=repository.lockAttempt(id);
                var e=repository.lockResultEvent(id);
                if(e==null) { if(!"PENDING".equals(a.resultStatus())) { throw new IllegalStateException("PAYMENT_RESULT_EVENT_MISSING"); }return; }
                // Never adopt a round created while this execution was waiting for business locks.
                if(!generation[0].matches(e)) { return; }
                lockOperations(id,generation[0].round());
                runtime.at("P2_LOCKS_ACQUIRED",id);
                Instant now=runtime.afterLocks(clock);
                if(now.isBefore(e.nextAttemptAt())) { return; }
                try {
                    validateTrusted(locked,a,e,false);
                } catch(IllegalStateException inconsistent) {
                    diagnostics.record(inconsistent);review(id,e.id(),now,"TRUSTED_RESULT_INCONSISTENT");
                    completeOperations(id,generation[0].round(),"REVIEW",now);return;
                }
                boolean success="SUCCESS".equals(a.resultStatus());
                boolean eligible="HOLD".equals(o.get("status")) && now.isBefore(instant(o.get("pay_expires_at")));
                if(success && eligible) {
                    repository.markOrderPaid(id,now,number(o,"id"));
                    runtime.at("P2_AFTER_ORDER",id);
                    settlement(id,"APPLIED",now);
                } else {
                    if("HOLD".equals(o.get("status")) && !eligible) { close.closeLocked(locked,now,false); }
                    if(success) {
                        repository.reverse(id,now,UUID.randomUUID().toString());
                    } else { settlement(id,"NO_CHARGE",now); }
                }
                runtime.at("P2_AFTER_SETTLEMENT",id);
                repository.finishEvent(e.id(),now);
                String result=success?(eligible?"APPLIED":"REVERSED"):"NO_CHARGE";
                completeOperations(id,generation[0].round(),result,now);
                runtime.at("P2_BEFORE_COMMIT",id);
            });
            runtime.at("P2_COMMITTED",id);
        } catch(RuntimeException failure) {
            diagnostics.record(new IllegalStateException("PAYMENT_SETTLEMENT_FAILED:"+id,failure));
            if(generation[0]!=null) {
                try { recordFailure(id,generation[0]); } catch(RuntimeException countFailure) { diagnostics.record(countFailure); }
            }
        }
    }
    private void settlement(long id,String state,Instant now) {
        repository.settle(id,state,now);
    }
    private void review(long id,long event,Instant now,String reason) {
        repository.markForReview(id,reason);
        repository.deadEvent(event,now,reason);
    }
    private void recordFailure(long id,Generation observed) {
        runtime.at("P2_BEFORE_FAILURE_RECORD",id);
        tx.executeWithoutResult(status->{
            lockRoute(id);var a=repository.lockAttempt(id);
            var e=repository.requireLockedResultEvent(id);
            if(!observed.matches(e) || !"UNRESOLVED".equals(a.settlementStatus())) { return; }
            lockOperations(id,observed.round());
            Instant now=runtime.afterLocks(clock);int count=observed.attempts()+1;
            if(now.isBefore(observed.due())) { return; }
            repository.countFailure(e.id(),count);
            if(count>=6) {
                review(id,e.id(),now,"RETRY_EXHAUSTED");
                completeOperations(id,observed.round(),"REVIEW",now);
            }
            else {
                long delay=(1L<<(count-1))*1000;delay+=ThreadLocalRandom.current().nextLong(delay/5+1);
                repository.scheduleRetry(e.id(),now.plusMillis(delay));
            }
        });
    }
    public void lockOperations(long id,long round) {
        repository.lockOperations(id,round);
    }
    private void completeOperations(long id,long round,String result,Instant now) {
        repository.completeOperations(id,round,result,now);
        runtime.at("P2_AFTER_AUDIT",id);
    }
    /** Shared validation for automatic settlement and the explicitly authorised recovery transaction. */
    public void validateTrusted(OrderCloseService.LockedOrder locked,PaymentAttemptPO a,PaymentResultEventPO e,boolean allowReview) {
        var o=locked.order();long id=a.id();
        association(a,o);
        if(!OrderCloseService.validSource(locked)) {
            throw new IllegalStateException("PAYMENT_REQUEST_INCONSISTENT");
        }
        String state=a.settlementStatus();
        if(e.paymentAttemptId()!=id || e.aggregateId()!=id
            || !Set.of("SUCCESS","FAILED").contains(a.resultStatus())
            || a.resultDigest()==null
            || !MessageDigest.isEqual(digest(a),a.resultDigest())
            || !("UNRESOLVED".equals(state) || (allowReview && "REVIEW".equals(state)))) {
            throw new IllegalStateException("PAYMENT_TRUSTED_RESULT_INCONSISTENT");
        }
        if("HOLD".equals(o.get("status"))) {
            if(o.get("current_attempt_id")==null || number(o,"current_attempt_id")!=id) { throw new IllegalStateException("PAYMENT_CURRENT_ATTEMPT_INCONSISTENT"); }
            assertActive(locked);
        } else if(!Set.of("CANCELLED","EXPIRED").contains(o.get("status"))) {
            throw new IllegalStateException("PAYMENT_UNRESOLVED_ORDER_INCONSISTENT");
        }
    }
    private static void association(PaymentAttemptPO a,Map<String,Object> o) {
        if(a.orderId()!=number(o,"id") || a.userId()!=number(o,"user_id")
            || a.sessionId()!=number(o,"session_id") || a.amountCent()!=number(o,"payable_cent")
            || !Objects.equals(a.currency(),o.get("currency"))) { throw new IllegalStateException("PAYMENT_ASSOCIATION_INCONSISTENT"); }
    }
    private void assertActive(OrderCloseService.LockedOrder locked) {
        var o=locked.order();var q=locked.quota();long id=number(o,"id");
        if(q==null || !"ORDER".equals(q.get("owner_type")) || number(q,"owner_id")!=id
            || !OrderCloseService.validSource(locked)) { throw new IllegalStateException("PAYMENT_OWNER_INCONSISTENT"); }
        var rows=repository.reservationLedger(id);
        if(rows.size()!=1 || !"RESERVE".equals(rows.get(0).action()) || rows.get(0).delta()!=-1
            || rows.get(0).tierId()!=number(o,"tier_id")) { throw new IllegalStateException("PAYMENT_LEDGER_INCONSISTENT"); }
    }
    public static byte[] digest(PaymentAttemptPO a) {
        try {
            String text="v1|"+a.id()+"|"+a.orderId()+"|"+a.userId()+"|"+a.sessionId()+"|"+a.amountCent()+"|"+a.currency()+"|"+a.resultStatus()+"|"+a.resultId()+"|"+time(a.resultAt())+"|"+a.resultReason();
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
