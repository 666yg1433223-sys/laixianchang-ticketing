package com.ticketing.booking.service.impl;

import com.ticketing.booking.service.*;
import com.ticketing.booking.repository.*;
import com.ticketing.booking.model.po.*;
import com.ticketing.booking.model.dto.*;
import com.ticketing.booking.model.vo.*;

import com.ticketing.booking.security.PaymentManagementAccess.Operator;
import com.ticketing.booking.config.RuntimeServices;
import com.ticketing.booking.domain.ApiFailure;

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
public class PaymentManagementServiceImpl implements PaymentManagementService {
    private static final Set<String> TERMINAL=Set.of("APPLIED","NO_CHARGE","REVERSED");
    private final PaymentManagementRepository repository;
    private final PaymentRepository attempts;
    private final DatabaseTimeRepository clock;
    private final PaymentService payment;
    private final RuntimeServices runtime;
    private final TransactionTemplate tx;

    public PaymentManagementServiceImpl(PaymentManagementRepository repository,PaymentRepository attempts,DatabaseTimeRepository clock,PaymentService payment,RuntimeServices runtime,
            PlatformTransactionManager manager) {
        this.repository=repository;this.attempts=attempts;this.clock=clock;this.payment=payment;this.runtime=runtime;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(2);tx.setTimeout(8);
    }

    public PaymentReviewPageVO list(Operator actor,long after,int limit) {
        requireActor(actor);
        if(after<0 || limit<1 || limit>50) { throw new ApiFailure(400,"INVALID_ARGUMENT"); }
        if(actor.attemptIds().isEmpty()) { return new PaymentReviewPageVO(List.of(),null); }
        try {
            var rows=repository.reviewPage(actor.attemptIds(),after,limit);
            var items=rows.stream().map(row->new PaymentReviewVO(Long.toString(row.id()),Long.toString(row.orderId()),row.resultId(),row.resultStatus(),
                row.settlementStatus(),"DEAD",Long.toString(row.recoveryGeneration()),row.attempts(),row.reviewReason(),row.lastErrorCode())).toList();
            String cursor=rows.size()<limit?null:Long.toString(rows.get(rows.size()-1).id());
            return new PaymentReviewPageVO(items,cursor);
        } catch(ApiFailure expected) { throw expected; }
        catch(RuntimeException unavailable) { throw new ApiFailure(503,"DEPENDENCY_UNAVAILABLE"); }
    }

    public PaymentOperationVO recover(Operator actor,PaymentRecoveryDTO request) {
        long attempt=request.attemptId();String key=request.idempotencyKey(),reason=request.reason();
        requireTarget(actor,attempt);
        return submit(actor,attempt,"RECOVER",Long.toString(attempt),key,normalizeReason(reason),1).receipt();
    }

    public PaymentOperationVO redeliver(Operator actor,PaymentRedeliveryDTO request) {
        String resultId=request.resultId(),key=request.idempotencyKey(),reason=request.reason();int deliveries=request.deliveries();
        requireActor(actor);uuid(resultId);
        if(deliveries<1 || deliveries>3) { throw new ApiFailure(400,"INVALID_ARGUMENT"); }
        if(actor.attemptIds().isEmpty()) { throw new ApiFailure(404,"RESOURCE_NOT_FOUND"); }
        try {
            var attempt=repository.findAttemptByResult(actor.attemptIds(),resultId);
            if(attempt==null) { throw new ApiFailure(404,"RESOURCE_NOT_FOUND"); }
            long id=attempt;
            var submitted=submit(actor,id,"REDELIVER",resultId,key,normalizeReason(reason),deliveries);
            var receipt=submitted.receipt();
            // The receipt and READY are already durable. A crash here is handled by the same scanner.
            for(int i=0;submitted.fresh() && i<deliveries && "ACCEPTED".equals(receipt.status());i++) {
                payment.settle(id);
                receipt=query(actor,receipt.operationId());
            }
            return receipt;
        } catch(ApiFailure expected) { throw expected; }
        catch(RuntimeException unavailable) { throw new ApiFailure(503,"OPERATION_ACCEPTANCE_UNKNOWN"); }
    }

    public PaymentOperationVO query(Operator actor,String operationId) {
        requireActor(actor);uuid(operationId);
        if(actor.attemptIds().isEmpty()) { throw new ApiFailure(404,"RESOURCE_NOT_FOUND"); }
        try {
            var row=repository.findOperation(actor.attemptIds(),operationId);
            if(row==null) { throw new ApiFailure(404,"RESOURCE_NOT_FOUND"); }
            return view(row);
        } catch(ApiFailure expected) { throw expected; }
        catch(RuntimeException unavailable) { throw new ApiFailure(503,"DEPENDENCY_UNAVAILABLE"); }
    }

    private record Submission(PaymentOperationVO receipt,boolean fresh) { }
    private Submission submit(Operator actor,long id,String action,String target,String key,String reason,int deliveries) {
        if(key==null || !key.matches("[A-Za-z0-9._:-]{8,64}")) { throw new ApiFailure(400,"INVALID_ARGUMENT"); }
        byte[] fingerprint=fingerprint(action,target,reason,deliveries);
        try {
            var previous=existing(actor,key);
            if(previous!=null) { return new Submission(repeat(previous,fingerprint),false); }
            // Scope-qualified read before any unqualified business route lookup.
            if(!attempts.attemptExists(id)) { throw new ApiFailure(404,"RESOURCE_NOT_FOUND"); }
            var receipt=tx.execute(status->{
                var locked=payment.lockRoute(id);
                var attempt=attempts.lockAttempt(id);
                var event=attempts.lockResultEventByAttempt(id);
                long round=event==null?0:event.recoveryGeneration();
                payment.lockOperations(id,round);
                var prior=existing(actor,key);
                if(prior!=null) { return new Submission(repeat(prior,fingerprint),false); }
                Instant now=runtime.afterLocks(clock);
                String rejection=null,completion=null;
                boolean recovering="RECOVER".equals(action);
                if(recovering) {
                    if(event==null || !"DEAD".equals(event.state()) || !"REVIEW".equals(attempt.settlementStatus())) {
                        rejection="NOT_RECOVERABLE";
                    } else if(round==Long.MAX_VALUE) { rejection="NOT_RECOVERABLE"; }
                    else {
                        try { payment.validateTrusted(locked,attempt,event,true); }
                        catch(IllegalStateException inconsistent) { rejection="TRUSTED_RESULT_INCONSISTENT"; }
                    }
                } else {
                    if(!Objects.equals(target,attempt.resultId())) { throw new ApiFailure(404,"RESOURCE_NOT_FOUND"); }
                    if(event!=null && "DONE".equals(event.state()) && TERMINAL.contains(attempt.settlementStatus())) {
                        completion=attempt.settlementStatus();
                    } else if(event!=null && "DEAD".equals(event.state())) { rejection="RECOVERY_REQUIRED"; }
                    else if(event==null || !"READY".equals(event.state())) { rejection="NOT_RECOVERABLE"; }
                    else {
                        try { payment.validateTrusted(locked,attempt,event,false); }
                        catch(IllegalStateException inconsistent) { rejection="TRUSTED_RESULT_INCONSISTENT"; }
                    }
                }
                boolean accepted=rejection==null;
                long operationRound=accepted && recovering?round+1:round;
                String operationId=UUID.randomUUID().toString();
                String state=!accepted?"REJECTED":completion!=null?"COMPLETED":"ACCEPTED";
                repository.recordOperation(new PaymentOperationPO(operationId,actor.id(),action,id,target,key,reason,deliveries,fingerprint,attempt.settlementStatus(),event==null?"MISSING":event.state(),event==null?0:event.attempts(),event==null?null:event.lastErrorCode(),operationRound,accepted?now:null,!accepted || completion!=null?now:null,state,completion,rejection));
                runtime.at("MGMT_AFTER_AUDIT",id);
                if(accepted && recovering) {
                    attempts.reopenEvent(event.id(),round,operationRound,now);
                    runtime.at("MGMT_AFTER_GENERATION",id);
                    attempts.reopenSettlement(id);
                    runtime.at("MGMT_AFTER_REOPEN",id);
                }
                runtime.at("MGMT_BEFORE_COMMIT",id);
                return new Submission(view(repository.requireOperation(operationId)),true);
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

    private PaymentOperationPO existing(Operator actor,String key) {
        if(actor.attemptIds().isEmpty()) { return null; }
        return repository.findByKey(actor.attemptIds(),actor.id(),key);
    }
    private static PaymentOperationVO repeat(PaymentOperationPO row,byte[] digest) {
        if(!MessageDigest.isEqual(row.parameterDigest(),digest)) { throw new ApiFailure(409,"IDEMPOTENCY_CONFLICT"); }
        return view(row);
    }
    private static PaymentOperationVO view(PaymentOperationPO row) {
        return new PaymentOperationVO(row.operationId(),Long.toString(row.operatorId()),Long.toString(row.attemptId()),Long.toString(row.generation()),
            row.action(),row.targetId(),row.reason(),row.deliveries(),row.status(),row.result(),row.beforeSettlement(),row.beforeEvent(),row.beforeAttempts(),
            row.beforeError(),row.acceptedAt()==null?null:time(row.acceptedAt()),row.completedAt()==null?null:time(row.completedAt()),row.rejectionReason(),
            "/v1/internal/sim/operations/"+row.operationId(),true);
    }
    public static String normalizeReason(String reason) {
        if(reason==null || reason.codePoints().anyMatch(Character::isISOControl)) { throw new ApiFailure(400,"INVALID_ARGUMENT"); }
        String result=reason.trim();
        if(result.isEmpty() || result.codePointCount(0,result.length())>200) { throw new ApiFailure(400,"INVALID_ARGUMENT"); }
        return result;
    }
    public static byte[] fingerprint(String action,String target,String reason,int deliveries) {
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
    private static void uuid(String id) {
        if(id==null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) { throw new ApiFailure(400,"INVALID_ARGUMENT"); }
    }
}
