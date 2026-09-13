package com.ticketing.booking.service;

import com.ticketing.booking.config.Diagnostics;
import com.ticketing.booking.config.RuntimeServices;
import com.ticketing.booking.domain.ApiFailure;
import com.ticketing.booking.persistence.BookingStore;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
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
public class RefundService {
    private final BookingStore store;
    private final RuntimeServices runtime;
    private final OrderCloseService close;
    private final Diagnostics diagnostics;
    private final TransactionTemplate tx;
    private final boolean switchEnabled;

    public RefundService(BookingStore store,RuntimeServices runtime,OrderCloseService close,Diagnostics diagnostics,
            PlatformTransactionManager manager,@Value("${biz.simulation.enabled:false}") boolean switchEnabled) {
        this.store=store;this.runtime=runtime;this.close=close;this.diagnostics=diagnostics;this.switchEnabled=switchEnabled;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(2);tx.setTimeout(8);
    }

    public boolean enabled() { return switchEnabled && runtime.productEnabled() && runtime.simulatorEnabled(); }
    private void requireEnabled() { if(!enabled()) { throw new ApiFailure(503,"REFUND_DISABLED"); } }

    public Map<String,Object> accept(long user,long orderId,String key,String suppliedReason) {
        requireEnabled();validateKey(key);String reason=reason(suppliedReason);
        close.query(user,orderId);
        var prior=existing(user,orderId,key,reason);
        if(prior!=null) { return receipt(prior); }
        try {
            runtime.at("R0_IDEMPOTENCY_MISS",orderId);
            long id=tx.execute(status -> {
                var locked=close.lockOrder(orderId,user);var order=locked.order();
                // Keep the common lock prefix, then payment attempt, then refund, then recovery audit.
                Map<String,Object> paid=order.get("paid_attempt_id")==null?null:
                    store.required("SELECT * FROM payment_attempt WHERE id=? FOR UPDATE",number(order,"paid_attempt_id"));
                var previous=store.optional("SELECT * FROM refund_request WHERE user_id=? AND idempotency_key=? FOR UPDATE",user,key);
                if(previous!=null) { checkKey(previous,orderId,reason);return number(previous,"id"); }
                var current=store.optional("SELECT id FROM refund_request WHERE order_id=? FOR UPDATE",orderId);
                if(current!=null || order.get("current_refund_id")!=null) { throw new ApiFailure(409,"REFUND_ALREADY_REQUESTED"); }
                runtime.at("R0_LOCKS_ACQUIRED",orderId);
                Instant now=runtime.afterLocks(store.jdbc());
                if(!"PAID".equals(order.get("status"))) { throw new ApiFailure(409,"ORDER_NOT_REFUNDABLE"); }
                // Session was already SHARE locked before any inventory/order lock; published start is immutable.
                var session=store.required("SELECT start_at FROM event_session WHERE id=?",number(order,"session_id"));
                if(!now.isBefore(instant(session.get("start_at")))) { throw new ApiFailure(409,"REFUND_WINDOW_CLOSED"); }
                assertPaid(locked,paid);assertActive(locked);
                long refund=store.insert("INSERT INTO refund_request(order_id,user_id,session_id,payment_attempt_id,idempotency_key,amount_cent,currency,reason,requested_at,next_attempt_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    orderId,user,number(order,"session_id"),number(paid,"id"),key,number(order,"payable_cent"),order.get("currency"),reason,sqlTime(now),sqlTime(now));
                runtime.at("R0_AFTER_REQUEST",refund);
                one(store.jdbc().update("UPDATE booking_order SET status='REFUNDING',current_refund_id=? WHERE id=? AND user_id=? AND status='PAID' AND current_refund_id IS NULL",refund,orderId,user));
                runtime.at("R0_AFTER_ORDER",refund);
                runtime.at("R0_BEFORE_COMMIT",refund);
                return refund;
            });
            runtime.at("R0_COMMITTED",id);
            runtime.at("BEFORE_REFUND_RESPONSE",id);
            return receipt(store.required("SELECT * FROM refund_request WHERE id=?",id));
        } catch(ApiFailure expected) { throw expected; }
        catch(DuplicateKeyException duplicate) {
            var visible=existing(user,orderId,key,reason);if(visible!=null) { return receipt(visible); }
            throw new ApiFailure(503,"REFUND_ACCEPTANCE_UNKNOWN").causedBy(duplicate);
        } catch(RuntimeException uncertain) {
            throw new ApiFailure(503,"REFUND_ACCEPTANCE_UNKNOWN").causedBy(uncertain);
        }
    }

    public Map<String,Object> query(long user,long id) {
        requireEnabled();
        var row=store.optional("SELECT r.*,o.status AS order_status FROM refund_request r JOIN booking_order o ON o.id=r.order_id AND o.user_id=r.user_id AND o.session_id=r.session_id WHERE r.id=? AND r.user_id=?",id,user);
        if(row==null) { throw new ApiFailure(404,"RESOURCE_NOT_FOUND"); }
        return view(row);
    }

    /** Controller performs requireAdmin before this cross-owner diagnostic query. */
    public Map<String,Object> queryForAdmin(long id) {
        requireEnabled();
        var row=store.optional("SELECT r.*,o.status AS order_status FROM refund_request r JOIN booking_order o ON o.id=r.order_id AND o.user_id=r.user_id AND o.session_id=r.session_id WHERE r.id=?",id);
        if(row==null) { throw new ApiFailure(404,"RESOURCE_NOT_FOUND"); }
        return view(row);
    }

    public Map<String,Object> listForAdmin(String status,int page,int size) {
        requireEnabled();
        if(!java.util.Set.of("PENDING","SUCCEEDED","REVIEW").contains(status) || page<1 || page>10000 || size<1 || size>50) {
            throw new ApiFailure(400,"INVALID_ARGUMENT");
        }
        var rows=store.jdbc().queryForList("SELECT r.*,o.status AS order_status FROM refund_request r JOIN booking_order o ON o.id=r.order_id WHERE r.status=? ORDER BY r.id DESC LIMIT ? OFFSET ?",status,size+1,(page-1)*size);
        return Map.of("items",rows.stream().limit(size).map(RefundService::view).toList(),"page",page,"size",size,"hasNext",rows.size()>size);
    }

    private record Generation(long round,int attempts,Instant due) {
        boolean matches(Map<String,Object> row) {
            return "PENDING".equals(row.get("status")) && number(row,"recovery_generation")==round
                && number(row,"attempts")==attempts && row.get("next_attempt_at")!=null
                && instant(row.get("next_attempt_at")).equals(due);
        }
    }

    /** One atomic success: refund fact + order close + stock + exact quota owner + unique ledger release. */
    public void settle(long id) {
        if(!enabled()) { return; }
        Generation observed=null;
        try {
            var candidate=store.optional("SELECT recovery_generation,attempts,next_attempt_at FROM refund_request WHERE id=? AND status='PENDING'",id);
            if(candidate==null) { return; }
            observed=new Generation(number(candidate,"recovery_generation"),(int)number(candidate,"attempts"),instant(candidate.get("next_attempt_at")));
            final Generation generation=observed;
            runtime.at("R1_OBSERVED",id);
            tx.executeWithoutResult(status -> {
                var locked=lockRoute(id);var order=locked.order();
                var paid=lockPayment(order);
                var refund=store.required("SELECT * FROM refund_request WHERE id=? FOR UPDATE",id);
                // A stale worker must never adopt a retry generation created while it waited for locks.
                if(!generation.matches(refund)) { return; }
                runtime.at("R1_LOCKS_ACQUIRED",id);
                Instant now=runtime.afterLocks(store.jdbc());
                if(now.isBefore(generation.due())) { return; }
                assertRefund(locked,paid,refund);assertActive(locked);
                long orderId=number(order,"id"),tier=number(order,"tier_id"),user=number(order,"user_id"),session=number(order,"session_id");
                one(store.jdbc().update("UPDATE refund_request SET status='SUCCEEDED',completed_at=?,next_attempt_at=NULL,review_reason=NULL,last_error_code=NULL WHERE id=? AND status='PENDING' AND recovery_generation=?",
                    sqlTime(now),id,generation.round()));
                runtime.at("R1_AFTER_REFUND",id);
                one(store.jdbc().update("UPDATE booking_order SET status='REFUNDED',closed_at=?,close_reason='USER_REFUNDED',refunded_at=? WHERE id=? AND status='REFUNDING' AND current_refund_id=?",
                    sqlTime(now),sqlTime(now),orderId,id));
                runtime.at("R1_AFTER_ORDER",id);
                one(store.jdbc().update("UPDATE ticket_tier SET available=available+1 WHERE id=? AND available<capacity",tier));
                runtime.at("R1_AFTER_STOCK",id);
                one(store.jdbc().update("UPDATE user_session_quota SET owner_type='NONE',owner_id=NULL,updated_at=? WHERE user_id=? AND session_id=? AND owner_type='ORDER' AND owner_id=?",
                    sqlTime(now),user,session,orderId));
                runtime.at("R1_AFTER_QUOTA",id);
                store.insert("INSERT INTO inventory_ledger(tier_id,order_id,action,delta,available_after,created_at) VALUES(?,?,'RELEASE',1,?,?)",
                    tier,orderId,number(locked.inventory(),"available")+1,sqlTime(now));
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
            var ids=store.jdbc().queryForList("SELECT id FROM refund_request WHERE status='PENDING' AND next_attempt_at<=UTC_TIMESTAMP(3) ORDER BY next_attempt_at,id LIMIT 20",Long.class);
            for(long id:ids) { settle(id); }
        } catch(RuntimeException failure) { diagnostics.record(failure); }
    }

    private void recordFailure(long id,Generation observed) {
        runtime.at("R1_BEFORE_FAILURE_RECORD",id);
        tx.executeWithoutResult(status -> {
            var locked=lockRoute(id);lockPayment(locked.order());
            var refund=store.required("SELECT * FROM refund_request WHERE id=? FOR UPDATE",id);
            if(!observed.matches(refund)) { return; }
            Instant now=runtime.afterLocks(store.jdbc());
            if(now.isBefore(observed.due())) { return; }
            int count=observed.attempts()+1;
            if(count>=6) {
                one(store.jdbc().update("UPDATE refund_request SET status='REVIEW',attempts=?,next_attempt_at=NULL,review_reason='RETRY_EXHAUSTED',last_error_code='REFUND_TECHNICAL_FAILURE' WHERE id=? AND status='PENDING' AND recovery_generation=?",
                    count,id,observed.round()));
            } else {
                long delay=(1L<<(count-1))*1000;delay+=ThreadLocalRandom.current().nextLong(delay/5+1);
                one(store.jdbc().update("UPDATE refund_request SET attempts=?,next_attempt_at=?,last_error_code='REFUND_TECHNICAL_FAILURE' WHERE id=? AND status='PENDING' AND recovery_generation=?",
                    count,sqlTime(now.plusMillis(delay)),id,observed.round()));
            }
        });
    }

    /** A technical recovery keeps the refund ID and the original requested time/amount/payment untouched. */
    public Map<String,Object> retry(long operator,long id,String key,String suppliedReason) {
        requireEnabled();validateKey(key);String reason=reason(suppliedReason);
        var route=store.optional("SELECT id FROM refund_request WHERE id=?",id);
        if(route==null) { throw new ApiFailure(404,"RESOURCE_NOT_FOUND"); }
        var prior=existingRetry(operator,id,key,reason);
        if(prior!=null) { return receipt(store.required("SELECT * FROM refund_request WHERE id=?",id)); }
        try {
            tx.executeWithoutResult(status -> {
                var locked=lockRoute(id);var paid=lockPayment(locked.order());
                var refund=store.required("SELECT * FROM refund_request WHERE id=? FOR UPDATE",id);
                var previous=store.optional("SELECT * FROM refund_retry WHERE operator_id=? AND idempotency_key=? FOR UPDATE",operator,key);
                if(previous!=null) { checkRetry(previous,id,reason);return; }
                if(!"REVIEW".equals(refund.get("status"))) { throw new ApiFailure(409,"REFUND_NOT_RECOVERABLE"); }
                try { assertRefund(locked,paid,refund);assertActive(locked); }
                catch(IllegalStateException inconsistent) { throw new ApiFailure(409,"REFUND_RECOVERY_INCONSISTENT").causedBy(inconsistent); }
                runtime.at("R2_LOCKS_ACQUIRED",id);
                Instant now=runtime.afterLocks(store.jdbc());
                long generation=Math.addExact(number(refund,"recovery_generation"),1);
                one(store.jdbc().update("UPDATE refund_request SET status='PENDING',attempts=0,recovery_generation=?,next_attempt_at=?,review_reason=NULL,last_error_code=NULL WHERE id=? AND status='REVIEW' AND recovery_generation=?",
                    generation,sqlTime(now),id,number(refund,"recovery_generation")));
                store.insert("INSERT INTO refund_retry(refund_id,operator_id,idempotency_key,reason,generation,created_at) VALUES(?,?,?,?,?,?)",
                    id,operator,key,reason,generation,sqlTime(now));
                runtime.at("R2_AFTER_AUDIT",id);
                runtime.at("R2_BEFORE_COMMIT",id);
            });
            runtime.at("R2_COMMITTED",id);
            return receipt(store.required("SELECT * FROM refund_request WHERE id=?",id));
        } catch(ApiFailure expected) { throw expected; }
        catch(DuplicateKeyException duplicate) {
            var visible=existingRetry(operator,id,key,reason);
            if(visible!=null) { return receipt(store.required("SELECT * FROM refund_request WHERE id=?",id)); }
            throw new ApiFailure(503,"REFUND_RECOVERY_UNKNOWN").causedBy(duplicate);
        } catch(RuntimeException uncertain) { throw new ApiFailure(503,"REFUND_RECOVERY_UNKNOWN").causedBy(uncertain); }
    }

    private OrderCloseService.LockedOrder lockRoute(long id) {
        var route=store.required("SELECT order_id FROM refund_request WHERE id=?",id);
        return close.lockOrder(number(route,"order_id"),null);
    }
    private Map<String,Object> lockPayment(Map<String,Object> order) {
        if(order.get("paid_attempt_id")==null) { throw new IllegalStateException("REFUND_PAYMENT_MISSING"); }
        return store.required("SELECT * FROM payment_attempt WHERE id=? FOR UPDATE",number(order,"paid_attempt_id"));
    }
    private void assertPaid(OrderCloseService.LockedOrder locked,Map<String,Object> paid) {
        var order=locked.order();
        if(paid==null || order.get("paid_attempt_id")==null || order.get("current_attempt_id")==null || order.get("paid_at")==null
            || number(paid,"id")!=number(order,"paid_attempt_id") || number(paid,"id")!=number(order,"current_attempt_id")
            || number(paid,"order_id")!=number(order,"id") || number(paid,"user_id")!=number(order,"user_id")
            || number(paid,"session_id")!=number(order,"session_id") || number(paid,"amount_cent")!=number(order,"payable_cent")
            || !Objects.equals(paid.get("currency"),order.get("currency"))
            || !"SUCCESS".equals(paid.get("result_status")) || !"APPLIED".equals(paid.get("settlement_status"))
            || !(paid.get("result_digest") instanceof byte[] digest) || !MessageDigest.isEqual(PaymentService.digest(paid),digest)) {
            throw new IllegalStateException("REFUND_PAYMENT_INCONSISTENT");
        }
    }
    private void assertRefund(OrderCloseService.LockedOrder locked,Map<String,Object> paid,Map<String,Object> refund) {
        var order=locked.order();assertPaid(locked,paid);
        if(!"REFUNDING".equals(order.get("status")) || order.get("current_refund_id")==null
            || number(order,"current_refund_id")!=number(refund,"id") || number(order,"id")!=number(refund,"order_id")
            || number(order,"user_id")!=number(refund,"user_id") || number(order,"session_id")!=number(refund,"session_id")
            || number(paid,"id")!=number(refund,"payment_attempt_id") || number(paid,"amount_cent")!=number(refund,"amount_cent")
            || !Objects.equals(paid.get("currency"),refund.get("currency"))) {
            throw new IllegalStateException("REFUND_ASSOCIATION_INCONSISTENT");
        }
    }
    private void assertActive(OrderCloseService.LockedOrder locked) {
        var order=locked.order();var quota=locked.quota();long id=number(order,"id");
        if(!OrderCloseService.validSource(locked) || quota==null || !"ORDER".equals(quota.get("owner_type")) || number(quota,"owner_id")!=id) {
            throw new IllegalStateException("REFUND_OWNER_INCONSISTENT");
        }
        var ledger=store.jdbc().queryForList("SELECT action,delta,tier_id FROM inventory_ledger WHERE order_id=?",id);
        if(ledger.size()!=1 || !"RESERVE".equals(ledger.get(0).get("action")) || number(ledger.get(0),"delta")!=-1
            || number(ledger.get(0),"tier_id")!=number(order,"tier_id")) { throw new IllegalStateException("REFUND_LEDGER_INCONSISTENT"); }
    }
    private Map<String,Object> existing(long user,long order,String key,String reason) {
        var row=store.optional("SELECT * FROM refund_request WHERE user_id=? AND idempotency_key=?",user,key);
        if(row!=null) { checkKey(row,order,reason); }return row;
    }
    private Map<String,Object> existingRetry(long operator,long id,String key,String reason) {
        var row=store.optional("SELECT * FROM refund_retry WHERE operator_id=? AND idempotency_key=?",operator,key);
        if(row!=null) { checkRetry(row,id,reason); }return row;
    }
    private static void checkKey(Map<String,Object> row,long order,String reason) {
        if(number(row,"order_id")!=order || !reason.equals(row.get("reason"))) { throw new ApiFailure(409,"IDEMPOTENCY_CONFLICT"); }
    }
    private static void checkRetry(Map<String,Object> row,long id,String reason) {
        if(number(row,"refund_id")!=id || !reason.equals(row.get("reason"))) { throw new ApiFailure(409,"IDEMPOTENCY_CONFLICT"); }
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
    private static Map<String,Object> receipt(Map<String,Object> row) {
        return Map.of("refundId",Long.toString(number(row,"id")),"orderId",Long.toString(number(row,"order_id")),
            "statusUrl","/v1/refunds/"+number(row,"id"),"status",row.get("status"),"simulated",true);
    }
    private static Map<String,Object> view(Map<String,Object> row) {
        var result=new LinkedHashMap<String,Object>(receipt(row));
        for(var field:Map.of("amountCent","amount_cent","currency","currency","reason","reason","orderStatus","order_status",
                "reviewReason","review_reason","attempts","attempts","recoveryGeneration","recovery_generation","lastError","last_error_code").entrySet()) {
            result.put(field.getKey(),row.get(field.getValue()));
        }
        result.put("requestedAt",time(row.get("requested_at")));
        result.put("completedAt",row.get("completed_at")==null?null:time(row.get("completed_at")));
        return result;
    }
}
