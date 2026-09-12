package com.ticketing.booking.service;

import com.ticketing.booking.config.*;
import com.ticketing.booking.domain.ApiFailure;
import com.ticketing.booking.persistence.BookingStore;
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
public class PaymentService {
    private final BookingStore store;
    private final RuntimeServices runtime;
    private final OrderCloseService close;
    private final Diagnostics diagnostics;
    private final TransactionTemplate tx;
    private final boolean switchEnabled;
    public PaymentService(BookingStore store,RuntimeServices runtime,OrderCloseService close,Diagnostics diagnostics,
            PlatformTransactionManager manager,@Value("${biz.simulation.enabled:false}") boolean switchEnabled) {
        this.store=store;this.runtime=runtime;this.close=close;this.diagnostics=diagnostics;this.switchEnabled=switchEnabled;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(2);tx.setTimeout(8);
    }
    public boolean enabled() { return switchEnabled && runtime.simulatorEnabled(); }
    public Map<String,Object> accept(long user,long order,String key) {
        if(key==null || !key.matches("[A-Za-z0-9._:-]{8,64}")) { throw new ApiFailure(400,"INVALID_ARGUMENT"); }
        close.query(user,order);
        if(!enabled()) { throw new ApiFailure(503,"SIMULATOR_DISABLED"); }
        var prior=existing(user,order,key); if(prior!=null) { return envelope(prior); }
        try {
            runtime.at("P0_IDEMPOTENCY_MISS",order);
            Long id=tx.execute(status->{
                var locked=close.lockOrder(order,user); var o=locked.order();
                // Repeat after order serialization: original key remains valid after any order terminal state.
                var previous=store.optional("SELECT * FROM payment_attempt WHERE user_id=? AND idempotency_key=? FOR UPDATE",user,key);
                if(previous!=null) { checkKeyOrder(previous,order);return number(previous,"id"); }
                Map<String,Object> current=null;
                if(o.get("current_attempt_id")!=null) {
                    current=store.required("SELECT * FROM payment_attempt WHERE id=? FOR UPDATE",number(o,"current_attempt_id"));
                    association(current,o);
                }
                runtime.at("P0_LOCKS_ACQUIRED",order);
                Instant now=runtime.afterLocks(store.jdbc());
                if(!"HOLD".equals(o.get("status"))) { throw new ApiFailure(409,"ORDER_NOT_PAYABLE"); }
                if(!now.isBefore(instant(o.get("pay_expires_at")))) {
                    close.closeLocked(locked,now,false);return null; // Commit C before translating to HTTP conflict.
                }
                assertActive(locked);
                if(current!=null && !("FAILED".equals(current.get("result_status")) && "NO_CHARGE".equals(current.get("settlement_status")))) {
                    throw new ApiFailure(409,"PAYMENT_IN_PROGRESS");
                }
                var plan=runtime.simulationPlan();
                long attempt=store.insert("INSERT INTO payment_attempt(order_id,user_id,session_id,idempotency_key,attempt_no,amount_cent,currency,created_at,planned_status,planned_at,resolution_deadline) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    order,user,number(o,"session_id"),key,current==null?1:number(current,"attempt_no")+1,number(o,"payable_cent"),o.get("currency"),sqlTime(now),plan.result(),sqlTime(now.plusMillis(plan.delayMillis())),sqlTime(now.plusSeconds(1800)));
                runtime.at("P0_AFTER_ATTEMPT",attempt);
                one(store.jdbc().update("UPDATE booking_order SET current_attempt_id=? WHERE id=? AND user_id=? AND status='HOLD'",attempt,order,user));
                runtime.at("P0_BEFORE_COMMIT",attempt);
                return attempt;
            });
            if(id==null) { throw new ApiFailure(409,"PAYMENT_DEADLINE_EXCEEDED"); }
            runtime.at("P0_COMMITTED",id);
            if(runtime.directEnabled()) { generate(id);settle(id); }
            runtime.at("BEFORE_PAYMENT_RESPONSE",id);
            return envelope(store.required("SELECT * FROM payment_attempt WHERE id=?",id));
        } catch(ApiFailure expected) { throw expected; }
        catch(org.springframework.dao.DuplicateKeyException duplicate) {
            var visible=existing(user,order,key);if(visible!=null) { return envelope(visible); }
            throw new ApiFailure(503,"PAYMENT_ACCEPTANCE_UNKNOWN").causedBy(duplicate);
        } catch(RuntimeException unknown) { throw new ApiFailure(503,"PAYMENT_ACCEPTANCE_UNKNOWN").causedBy(unknown); }
    }
    private Map<String,Object> existing(long user,long order,String key) {
        var a=store.optional("SELECT * FROM payment_attempt WHERE user_id=? AND idempotency_key=?",user,key);
        if(a!=null) { checkKeyOrder(a,order); }return a;
    }
    private static void checkKeyOrder(Map<String,Object> a,long order) {
        if(number(a,"order_id")!=order) { throw new ApiFailure(409,"IDEMPOTENCY_CONFLICT"); }
    }
    private static Map<String,Object> envelope(Map<String,Object> a) {
        return Map.of("attemptId",Long.toString(number(a,"id")),"orderId",Long.toString(number(a,"order_id")),
            "statusUrl","/v1/payment-attempts/"+number(a,"id"),"simulated",true,"message","模拟支付，无真实资金");
    }
    public Map<String,Object> query(long user,long id) {
        var a=store.optional("SELECT a.*,o.status AS order_status,o.current_attempt_id,o.paid_attempt_id,o.paid_at FROM payment_attempt a JOIN booking_order o ON o.id=a.order_id AND o.user_id=a.user_id AND o.session_id=a.session_id WHERE a.id=? AND a.user_id=?",id,user);
        if(a==null) { throw new ApiFailure(404,"RESOURCE_NOT_FOUND"); }
        Map<String,Object> v=new LinkedHashMap<>(envelope(a));
        for(var field:Map.of("resultStatus","result_status","settlementStatus","settlement_status","amountCent","amount_cent","currency","currency","orderStatus","order_status","resultId","result_id","resultReason","result_reason","reviewReason","review_reason","compensationId","compensation_id","compensationCent","compensation_cent").entrySet()) v.put(field.getKey(),a.get(field.getValue()));
        for(var field:Map.of("createdAt","created_at","resolutionDeadline","resolution_deadline","resultAt","result_at","settledAt","settled_at","compensationAt","compensation_at").entrySet()) v.put(field.getKey(),a.get(field.getValue())==null?null:time(a.get(field.getValue())));
        OrderCloseService.paymentLinks(a,v);return v;
    }
    OrderCloseService.LockedOrder lockRoute(long attempt) {
        var route=store.required("SELECT order_id FROM payment_attempt WHERE id=?",attempt);
        return close.lockOrder(number(route,"order_id"),null);
    }
    public void generate(long id) {
        if(!enabled()) { return; }
        tx.executeWithoutResult(status->{
            var locked=lockRoute(id);var a=store.required("SELECT * FROM payment_attempt WHERE id=? FOR UPDATE",id);
            association(a,locked.order());
            if(!"PENDING".equals(a.get("result_status"))) { return; }
            runtime.at("P1_LOCKS_ACQUIRED",id);
            Instant now=runtime.afterLocks(store.jdbc());
            boolean expired=!now.isBefore(instant(a.get("resolution_deadline")));
            if(!expired && now.isBefore(instant(a.get("planned_at")))) { return; }
            String result=expired?"FAILED":(String)a.get("planned_status");
            String reason=expired?"SIM_GENERATION_EXPIRED":result.equals("SUCCESS")?"SIM_SUCCESS":"SIM_DECLINED";
            a.put("result_status",result);a.put("result_id",UUID.randomUUID().toString());a.put("result_at",sqlTime(now));a.put("result_reason",reason);
            one(store.jdbc().update("UPDATE payment_attempt SET result_status=?,result_id=?,result_at=?,result_reason=?,result_digest=? WHERE id=? AND result_status='PENDING'",
                result,a.get("result_id"),sqlTime(now),reason,digest(a),id));
            runtime.at("P1_AFTER_RESULT",id);
            store.insert("INSERT INTO outbox_event(event_type,aggregate_id,payment_attempt_id,payload,next_attempt_at,created_at) VALUES('PAYMENT_RESULT_RECORDED',?,?,JSON_OBJECT('schemaVersion',1,'attemptId',?,'resultId',?),?,?)",
                id,id,Long.toString(id),a.get("result_id"),sqlTime(now),sqlTime(now));
            runtime.at("P1_BEFORE_COMMIT",id);
        });
        runtime.at("P1_COMMITTED",id);
    }
    record Generation(long round,int attempts,Instant due) {
        boolean matches(Map<String,Object> event) {
            return "READY".equals(event.get("state"))
                && number(event,"recovery_generation")==round
                && number(event,"attempts")==attempts
                && event.get("next_attempt_at")!=null
                && instant(event.get("next_attempt_at")).equals(due);
        }
    }
    public void settle(long id) {
        final Generation[] generation={null};
        try {
            // Observe a generation without locks so even a later lock-timeout can be counted safely.
            var observed=store.optional("SELECT recovery_generation,attempts,next_attempt_at FROM outbox_event WHERE payment_attempt_id=? AND event_type='PAYMENT_RESULT_RECORDED' AND state='READY'",id);
            if(observed==null) { return; }
            generation[0]=new Generation(number(observed,"recovery_generation"),(int)number(observed,"attempts"),instant(observed.get("next_attempt_at")));
            runtime.at("P2_OBSERVED",id);
            tx.executeWithoutResult(status->{
                var locked=lockRoute(id);var o=locked.order();
                var a=store.required("SELECT * FROM payment_attempt WHERE id=? FOR UPDATE",id);
                var e=store.optional("SELECT * FROM outbox_event WHERE event_type='PAYMENT_RESULT_RECORDED' AND aggregate_id=? FOR UPDATE",id);
                if(e==null) { if(!"PENDING".equals(a.get("result_status"))) { throw new IllegalStateException("PAYMENT_RESULT_EVENT_MISSING"); }return; }
                // Never adopt a round created while this execution was waiting for business locks.
                if(!generation[0].matches(e)) { return; }
                lockOperations(id,generation[0].round());
                runtime.at("P2_LOCKS_ACQUIRED",id);
                Instant now=runtime.afterLocks(store.jdbc());
                if(now.isBefore(instant(e.get("next_attempt_at")))) { return; }
                try {
                    validateTrusted(locked,a,e,false);
                } catch(IllegalStateException inconsistent) {
                    diagnostics.record(inconsistent);review(id,number(e,"id"),now,"TRUSTED_RESULT_INCONSISTENT");
                    completeOperations(id,generation[0].round(),"REVIEW",now);return;
                }
                boolean success="SUCCESS".equals(a.get("result_status"));
                boolean eligible="HOLD".equals(o.get("status")) && now.isBefore(instant(o.get("pay_expires_at")));
                if(success && eligible) {
                    one(store.jdbc().update("UPDATE booking_order SET status='PAID',paid_attempt_id=?,paid_at=? WHERE id=? AND status='HOLD' AND current_attempt_id=?",id,sqlTime(now),number(o,"id"),id));
                    runtime.at("P2_AFTER_ORDER",id);
                    settlement(id,"APPLIED",now);
                } else {
                    if("HOLD".equals(o.get("status")) && !eligible) { close.closeLocked(locked,now,false); }
                    if(success) {
                        one(store.jdbc().update("UPDATE payment_attempt SET settlement_status='REVERSED',settled_at=?,compensation_id=?,compensation_cent=amount_cent,compensation_at=? WHERE id=? AND settlement_status='UNRESOLVED'",
                            sqlTime(now),UUID.randomUUID().toString(),sqlTime(now),id));
                    } else { settlement(id,"NO_CHARGE",now); }
                }
                runtime.at("P2_AFTER_SETTLEMENT",id);
                one(store.jdbc().update("UPDATE outbox_event SET state='DONE',next_attempt_at=NULL,finished_at=?,last_error_code=NULL WHERE id=? AND state='READY'",sqlTime(now),number(e,"id")));
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
        one(store.jdbc().update("UPDATE payment_attempt SET settlement_status=?,settled_at=? WHERE id=? AND settlement_status='UNRESOLVED'",state,sqlTime(now),id));
    }
    private void review(long id,long event,Instant now,String reason) {
        one(store.jdbc().update("UPDATE payment_attempt SET settlement_status='REVIEW',review_reason=? WHERE id=? AND settlement_status='UNRESOLVED'",reason,id));
        one(store.jdbc().update("UPDATE outbox_event SET state='DEAD',next_attempt_at=NULL,finished_at=?,last_error_code=? WHERE id=? AND state='READY'",sqlTime(now),reason,event));
    }
    private void recordFailure(long id,Generation observed) {
        runtime.at("P2_BEFORE_FAILURE_RECORD",id);
        tx.executeWithoutResult(status->{
            lockRoute(id);var a=store.required("SELECT * FROM payment_attempt WHERE id=? FOR UPDATE",id);
            var e=store.required("SELECT * FROM outbox_event WHERE event_type='PAYMENT_RESULT_RECORDED' AND aggregate_id=? FOR UPDATE",id);
            if(!observed.matches(e) || !"UNRESOLVED".equals(a.get("settlement_status"))) { return; }
            lockOperations(id,observed.round());
            Instant now=runtime.afterLocks(store.jdbc());int count=observed.attempts()+1;
            if(now.isBefore(observed.due())) { return; }
            one(store.jdbc().update("UPDATE outbox_event SET attempts=?,last_error_code='PAYMENT_TECHNICAL_FAILURE' WHERE id=?",count,number(e,"id")));
            if(count>=6) {
                review(id,number(e,"id"),now,"RETRY_EXHAUSTED");
                completeOperations(id,observed.round(),"REVIEW",now);
            }
            else {
                long delay=(1L<<(count-1))*1000;delay+=ThreadLocalRandom.current().nextLong(delay/5+1);
                one(store.jdbc().update("UPDATE outbox_event SET next_attempt_at=? WHERE id=? AND state='READY'",sqlTime(now.plusMillis(delay)),number(e,"id")));
            }
        });
    }
    void lockOperations(long id,long round) {
        store.jdbc().queryForList("SELECT operation_id FROM sim_payment_operation WHERE attempt_id=? AND generation=? AND status='ACCEPTED' ORDER BY operation_id FOR UPDATE",id,round);
    }
    private void completeOperations(long id,long round,String result,Instant now) {
        store.jdbc().update("UPDATE sim_payment_operation SET status='COMPLETED',result=?,completed_at=? WHERE attempt_id=? AND generation=? AND status='ACCEPTED'",result,sqlTime(now),id,round);
        runtime.at("P2_AFTER_AUDIT",id);
    }
    /** Shared validation for automatic settlement and the explicitly authorised recovery transaction. */
    void validateTrusted(OrderCloseService.LockedOrder locked,Map<String,Object> a,Map<String,Object> e,boolean allowReview) {
        var o=locked.order();long id=number(a,"id");
        association(a,o);
        if(!OrderCloseService.validSource(locked)) {
            throw new IllegalStateException("PAYMENT_REQUEST_INCONSISTENT");
        }
        String state=(String)a.get("settlement_status");
        if(number(e,"payment_attempt_id")!=id || number(e,"aggregate_id")!=id
            || !Set.of("SUCCESS","FAILED").contains(a.get("result_status"))
            || !(a.get("result_digest") instanceof byte[] storedDigest)
            || !MessageDigest.isEqual(digest(a),storedDigest)
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
    private static void association(Map<String,Object> a,Map<String,Object> o) {
        if(number(a,"order_id")!=number(o,"id") || number(a,"user_id")!=number(o,"user_id")
            || number(a,"session_id")!=number(o,"session_id") || number(a,"amount_cent")!=number(o,"payable_cent")
            || !Objects.equals(a.get("currency"),o.get("currency"))) { throw new IllegalStateException("PAYMENT_ASSOCIATION_INCONSISTENT"); }
    }
    private void assertActive(OrderCloseService.LockedOrder locked) {
        var o=locked.order();var q=locked.quota();var r=locked.request();long id=number(o,"id");
        if(q==null || !"ORDER".equals(q.get("owner_type")) || number(q,"owner_id")!=id
            || !OrderCloseService.validSource(locked)) { throw new IllegalStateException("PAYMENT_OWNER_INCONSISTENT"); }
        var rows=store.jdbc().queryForList("SELECT action,delta,tier_id FROM inventory_ledger WHERE order_id=?",id);
        if(rows.size()!=1 || !"RESERVE".equals(rows.get(0).get("action")) || number(rows.get(0),"delta")!=-1
            || number(rows.get(0),"tier_id")!=number(o,"tier_id")) { throw new IllegalStateException("PAYMENT_LEDGER_INCONSISTENT"); }
    }
    private static byte[] digest(Map<String,Object> a) {
        try {
            String text="v1|"+number(a,"id")+"|"+number(a,"order_id")+"|"+number(a,"user_id")+"|"+number(a,"session_id")+"|"+number(a,"amount_cent")+"|"+a.get("currency")+"|"+a.get("result_status")+"|"+a.get("result_id")+"|"+time(a.get("result_at"))+"|"+a.get("result_reason");
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
