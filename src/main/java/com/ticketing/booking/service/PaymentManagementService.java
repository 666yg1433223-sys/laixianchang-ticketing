package com.ticketing.booking.service;

import com.ticketing.booking.auth.PaymentManagementAccess.Operator;
import com.ticketing.booking.config.RuntimeServices;
import com.ticketing.booking.domain.ApiFailure;
import com.ticketing.booking.persistence.BookingStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.ticketing.booking.domain.Values.*;

/** Only requests a new legal settlement round; never creates or edits a payment result. */
@Service
public class PaymentManagementService {
    private static final Set<String> TERMINAL=Set.of("APPLIED","NO_CHARGE","REVERSED");
    private final BookingStore store;
    private final PaymentService payment;
    private final RuntimeServices runtime;
    private final TransactionTemplate tx;

    public PaymentManagementService(BookingStore store,PaymentService payment,RuntimeServices runtime,
            PlatformTransactionManager manager) {
        this.store=store;this.payment=payment;this.runtime=runtime;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(2);tx.setTimeout(8);
    }

    public Map<String,Object> list(Operator actor,long after,int limit) {
        requireActor(actor);
        if(after<0 || limit<1 || limit>50) { throw new ApiFailure(400,"INVALID_ARGUMENT"); }
        if(actor.attemptIds().isEmpty()) { return page(List.of(),null); }
        try {
            var args=scopeArgs(actor);args.add(after);args.add(limit);
            var rows=store.jdbc().queryForList("SELECT a.id,a.order_id,a.result_id,a.result_status,a.settlement_status,a.review_reason,e.attempts,e.last_error_code,e.recovery_generation FROM payment_attempt a JOIN outbox_event e ON e.payment_attempt_id=a.id AND e.event_type='PAYMENT_RESULT_RECORDED' WHERE a.id IN ("+slots(actor)+") AND a.id>? AND a.settlement_status='REVIEW' AND e.state='DEAD' ORDER BY a.id LIMIT ?",args.toArray());
            var items=new ArrayList<Map<String,Object>>();
            for(var row:rows) {
                var item=new LinkedHashMap<String,Object>();
                item.put("attemptId",Long.toString(number(row,"id")));
                item.put("orderId",Long.toString(number(row,"order_id")));
                item.put("resultId",row.get("result_id"));item.put("resultStatus",row.get("result_status"));
                item.put("settlementStatus",row.get("settlement_status"));item.put("eventState","DEAD");
                item.put("generation",Long.toString(number(row,"recovery_generation")));
                item.put("attempts",number(row,"attempts"));item.put("reviewReason",row.get("review_reason"));
                item.put("lastError",row.get("last_error_code"));items.add(item);
            }
            String cursor=rows.size()<limit?null:Long.toString(number(rows.get(rows.size()-1),"id"));
            return page(items,cursor);
        } catch(ApiFailure expected) { throw expected; }
        catch(RuntimeException unavailable) { throw new ApiFailure(503,"DEPENDENCY_UNAVAILABLE"); }
    }

    public Map<String,Object> recover(Operator actor,long attempt,String key,String reason) {
        requireTarget(actor,attempt);
        return submit(actor,attempt,"RECOVER",Long.toString(attempt),key,normalizeReason(reason),1).receipt();
    }

    public Map<String,Object> redeliver(Operator actor,String resultId,String key,String reason,int deliveries) {
        requireActor(actor);uuid(resultId);
        if(deliveries<1 || deliveries>3) { throw new ApiFailure(400,"INVALID_ARGUMENT"); }
        if(actor.attemptIds().isEmpty()) { throw new ApiFailure(404,"RESOURCE_NOT_FOUND"); }
        try {
            var args=scopeArgs(actor);args.add(resultId);
            var attempt=store.optional("SELECT id FROM payment_attempt WHERE id IN ("+slots(actor)+") AND result_id=?",args.toArray());
            if(attempt==null) { throw new ApiFailure(404,"RESOURCE_NOT_FOUND"); }
            long id=number(attempt,"id");
            var submitted=submit(actor,id,"REDELIVER",resultId,key,normalizeReason(reason),deliveries);
            var receipt=submitted.receipt();
            // The receipt and READY are already durable. A crash here is handled by the same scanner.
            for(int i=0;submitted.fresh() && i<deliveries && "ACCEPTED".equals(receipt.get("status"));i++) {
                payment.settle(id);
                receipt=query(actor,(String)receipt.get("operationId"));
            }
            return receipt;
        } catch(ApiFailure expected) { throw expected; }
        catch(RuntimeException unavailable) { throw new ApiFailure(503,"OPERATION_ACCEPTANCE_UNKNOWN"); }
    }

    public Map<String,Object> query(Operator actor,String operationId) {
        requireActor(actor);uuid(operationId);
        if(actor.attemptIds().isEmpty()) { throw new ApiFailure(404,"RESOURCE_NOT_FOUND"); }
        try {
            var args=scopeArgs(actor);args.add(operationId);
            var row=store.optional("SELECT * FROM sim_payment_operation WHERE attempt_id IN ("+slots(actor)+") AND operation_id=?",args.toArray());
            if(row==null) { throw new ApiFailure(404,"RESOURCE_NOT_FOUND"); }
            return view(row);
        } catch(ApiFailure expected) { throw expected; }
        catch(RuntimeException unavailable) { throw new ApiFailure(503,"DEPENDENCY_UNAVAILABLE"); }
    }

    private record Submission(Map<String,Object> receipt,boolean fresh) { }
    private Submission submit(Operator actor,long id,String action,String target,String key,String reason,int deliveries) {
        if(key==null || !key.matches("[A-Za-z0-9._:-]{8,64}")) { throw new ApiFailure(400,"INVALID_ARGUMENT"); }
        byte[] fingerprint=fingerprint(action,target,reason,deliveries);
        try {
            var previous=existing(actor,key);
            if(previous!=null) { return new Submission(repeat(previous,fingerprint),false); }
            // Scope-qualified read before any unqualified business route lookup.
            if(store.optional("SELECT id FROM payment_attempt WHERE id=?",id)==null) { throw new ApiFailure(404,"RESOURCE_NOT_FOUND"); }
            var receipt=tx.execute(status->{
                var locked=payment.lockRoute(id);
                var attempt=store.required("SELECT * FROM payment_attempt WHERE id=? FOR UPDATE",id);
                var event=store.optional("SELECT * FROM outbox_event WHERE payment_attempt_id=? AND event_type='PAYMENT_RESULT_RECORDED' FOR UPDATE",id);
                long round=event==null?0:number(event,"recovery_generation");
                payment.lockOperations(id,round);
                var prior=existing(actor,key);
                if(prior!=null) { return new Submission(repeat(prior,fingerprint),false); }
                Instant now=runtime.afterLocks(store.jdbc());
                String rejection=null,completion=null;
                boolean recovering="RECOVER".equals(action);
                if(recovering) {
                    if(event==null || !"DEAD".equals(event.get("state")) || !"REVIEW".equals(attempt.get("settlement_status"))) {
                        rejection="NOT_RECOVERABLE";
                    } else if(round==Long.MAX_VALUE) { rejection="NOT_RECOVERABLE"; }
                    else {
                        try { payment.validateTrusted(locked,attempt,event,true); }
                        catch(IllegalStateException inconsistent) { rejection="TRUSTED_RESULT_INCONSISTENT"; }
                    }
                } else {
                    if(!Objects.equals(target,attempt.get("result_id"))) { throw new ApiFailure(404,"RESOURCE_NOT_FOUND"); }
                    if(event!=null && "DONE".equals(event.get("state")) && TERMINAL.contains(attempt.get("settlement_status"))) {
                        completion=(String)attempt.get("settlement_status");
                    } else if(event!=null && "DEAD".equals(event.get("state"))) { rejection="RECOVERY_REQUIRED"; }
                    else if(event==null || !"READY".equals(event.get("state"))) { rejection="NOT_RECOVERABLE"; }
                    else {
                        try { payment.validateTrusted(locked,attempt,event,false); }
                        catch(IllegalStateException inconsistent) { rejection="TRUSTED_RESULT_INCONSISTENT"; }
                    }
                }
                boolean accepted=rejection==null;
                long operationRound=accepted && recovering?round+1:round;
                String operationId=UUID.randomUUID().toString();
                String state=!accepted?"REJECTED":completion!=null?"COMPLETED":"ACCEPTED";
                one(store.jdbc().update("INSERT INTO sim_payment_operation(operation_id,operator_id,action,attempt_id,target_id,idempotency_key,reason,deliveries,parameter_digest,before_settlement,before_event,before_attempts,before_error,generation,accepted_at,completed_at,status,result,rejection_reason) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    operationId,actor.id(),action,id,target,key,reason,deliveries,fingerprint,attempt.get("settlement_status"),
                    event==null?"MISSING":event.get("state"),event==null?0:number(event,"attempts"),event==null?null:event.get("last_error_code"),
                    operationRound,accepted?sqlTime(now):null,!accepted || completion!=null?sqlTime(now):null,state,completion,rejection));
                runtime.at("MGMT_AFTER_AUDIT",id);
                if(accepted && recovering) {
                    one(store.jdbc().update("UPDATE outbox_event SET recovery_generation=?,state='READY',attempts=0,next_attempt_at=?,finished_at=NULL,last_error_code=NULL WHERE id=? AND state='DEAD' AND recovery_generation=?",
                        operationRound,sqlTime(now),number(event,"id"),round));
                    runtime.at("MGMT_AFTER_GENERATION",id);
                    one(store.jdbc().update("UPDATE payment_attempt SET settlement_status='UNRESOLVED',review_reason=NULL WHERE id=? AND settlement_status='REVIEW'",id));
                    runtime.at("MGMT_AFTER_REOPEN",id);
                }
                runtime.at("MGMT_BEFORE_COMMIT",id);
                return new Submission(view(store.required("SELECT * FROM sim_payment_operation WHERE operation_id=?",operationId)),true);
            });
            runtime.at("MGMT_COMMITTED",id);
            runtime.at("BEFORE_MANAGEMENT_RESPONSE",id);
            return receipt;
        } catch(ApiFailure expected) { throw expected; }
        catch(DuplicateKeyException race) {
            // Unique conflict rolls the whole transaction back before looking up the winning receipt.
            try {
                var winner=existing(actor,key);
                if(winner!=null) { return new Submission(repeat(winner,fingerprint),false); }
                throw new ApiFailure(409,"IDEMPOTENCY_CONFLICT");
            } catch(ApiFailure expected) { throw expected; }
            catch(RuntimeException unavailable) { throw new ApiFailure(503,"OPERATION_ACCEPTANCE_UNKNOWN"); }
        } catch(RuntimeException uncertain) { throw new ApiFailure(503,"OPERATION_ACCEPTANCE_UNKNOWN"); }
    }

    private Map<String,Object> existing(Operator actor,String key) {
        if(actor.attemptIds().isEmpty()) { return null; }
        var args=scopeArgs(actor);args.add(actor.id());args.add(key);
        return store.optional("SELECT * FROM sim_payment_operation WHERE attempt_id IN ("+slots(actor)+") AND operator_id=? AND idempotency_key=?",args.toArray());
    }
    private static Map<String,Object> repeat(Map<String,Object> row,byte[] digest) {
        if(!MessageDigest.isEqual((byte[])row.get("parameter_digest"),digest)) { throw new ApiFailure(409,"IDEMPOTENCY_CONFLICT"); }
        return view(row);
    }
    private static Map<String,Object> view(Map<String,Object> row) {
        var value=new LinkedHashMap<String,Object>();
        value.put("operationId",row.get("operation_id"));value.put("operatorId",Long.toString(number(row,"operator_id")));
        value.put("attemptId",Long.toString(number(row,"attempt_id")));value.put("generation",Long.toString(number(row,"generation")));
        value.put("action",row.get("action"));value.put("targetId",row.get("target_id"));value.put("reason",row.get("reason"));
        value.put("deliveries",number(row,"deliveries"));value.put("status",row.get("status"));value.put("result",row.get("result"));
        value.put("beforeSettlement",row.get("before_settlement"));value.put("beforeEvent",row.get("before_event"));
        value.put("beforeAttempts",number(row,"before_attempts"));value.put("beforeError",row.get("before_error"));
        value.put("acceptedAt",row.get("accepted_at")==null?null:time(row.get("accepted_at")));
        value.put("completedAt",row.get("completed_at")==null?null:time(row.get("completed_at")));
        value.put("rejectionReason",row.get("rejection_reason"));
        value.put("statusUrl","/v1/internal/sim/operations/"+row.get("operation_id"));
        value.put("simulated",true);
        return value;
    }
    private static Map<String,Object> page(List<Map<String,Object>> items,String cursor) {
        var value=new LinkedHashMap<String,Object>();value.put("items",items);value.put("nextAfterAttemptId",cursor);return value;
    }
    static String normalizeReason(String reason) {
        if(reason==null || reason.codePoints().anyMatch(Character::isISOControl)) { throw new ApiFailure(400,"INVALID_ARGUMENT"); }
        String result=reason.trim();
        if(result.isEmpty() || result.codePointCount(0,result.length())>200) { throw new ApiFailure(400,"INVALID_ARGUMENT"); }
        return result;
    }
    static byte[] fingerprint(String action,String target,String reason,int deliveries) {
        try {
            // Fixed fields plus a length-delimited free-text reason avoid delimiter ambiguity.
            String input=action+"|"+target+"|"+deliveries+"|"+reason.length()+":"+reason;
            return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        } catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void requireActor(Operator actor) {
        if(actor==null || actor.id()<=0) { throw new ApiFailure(403,"SIM_MANAGEMENT_FORBIDDEN"); }
    }
    private static void requireTarget(Operator actor,long target) {
        requireActor(actor);
        if(target<=0) { throw new ApiFailure(400,"INVALID_ARGUMENT"); }
        if(!actor.attemptIds().contains(target)) { throw new ApiFailure(404,"RESOURCE_NOT_FOUND"); }
    }
    private static String slots(Operator actor) { return String.join(",",Collections.nCopies(actor.attemptIds().size(),"?")); }
    private static ArrayList<Object> scopeArgs(Operator actor) { return new ArrayList<>(actor.attemptIds()); }
    private static void uuid(String id) {
        if(id==null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) { throw new ApiFailure(400,"INVALID_ARGUMENT"); }
    }
}
