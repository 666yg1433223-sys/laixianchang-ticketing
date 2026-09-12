package com.ticketing.booking.product;

import com.ticketing.booking.auth.AuthService;
import com.ticketing.booking.auth.PaymentManagementAccess.Operator;
import com.ticketing.booking.domain.ApiFailure;
import com.ticketing.booking.persistence.BookingStore;
import com.ticketing.booking.service.PaymentManagementService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import static com.ticketing.booking.domain.Values.*;

/** Local product admin: type-routed recovery; no arbitrary state/amount/inventory writes. */
@RestController
@Profile("local-sim")
@RequestMapping("/v1/admin/processing-failures")
public class ProcessingAdminController {
    private final BookingStore store;private final AuthService auth;private final PaymentManagementService payments;private final TransactionTemplate tx;
    public ProcessingAdminController(BookingStore store,AuthService auth,PaymentManagementService payments,PlatformTransactionManager manager) {
        this.store=store;this.auth=auth;this.payments=payments;tx=new TransactionTemplate(manager);tx.setIsolationLevel(2);tx.setTimeout(8);
    }
    @GetMapping public Map<String,Object> list(HttpServletRequest r,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        auth.requireAdmin(actor(r));int offset=ProductInput.page(page,size);
        var rows=store.jdbc().queryForList("SELECT id,event_type,aggregate_id,attempts,last_error_code,recovery_generation FROM outbox_event WHERE state='DEAD' ORDER BY id DESC LIMIT ? OFFSET ?",size+1,offset);
        var items=rows.stream().limit(size).map(row -> {
            var item=new LinkedHashMap<String,Object>();item.put("eventId",Long.toString(number(row,"id")));item.put("eventType",row.get("event_type"));
            item.put("aggregateId",Long.toString(number(row,"aggregate_id")));item.put("attempts",row.get("attempts"));item.put("lastError",row.get("last_error_code"));return item;
        }).toList();
        var metrics=store.jdbc().queryForMap("SELECT COUNT(*) AS acceptedRequests,COALESCE(MAX(TIMESTAMPDIFF(SECOND,accepted_at,UTC_TIMESTAMP(3))),0) AS oldestAcceptedSeconds FROM booking_request WHERE status='ACCEPTED'");
        return Map.of("items",items,"page",page,"size",size,"hasNext",rows.size()>size,"backlog",metrics,
            "invalidMessageCount",store.jdbc().queryForObject("SELECT COUNT(*) FROM invalid_stream_message",Long.class));
    }
    @PostMapping(value="/{eventId}/retry",consumes="application/json")
    public Map<String,Object> retry(HttpServletRequest r,@PathVariable String eventId,@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody String body) {
        long operator=actor(r);auth.requireAdmin(operator);ProductInput.key(key);long id=id(eventId);
        var n=ProductInput.body(body,Set.of("reason"));
        if(!n.path("reason").isTextual()) { throw new ApiFailure(400,"INVALID_ARGUMENT"); }
        String reason=n.path("reason").asText().trim();
        if(reason.isEmpty() || reason.length()>200 || reason.chars().anyMatch(Character::isISOControl)) { throw new ApiFailure(400,"INVALID_ARGUMENT"); }
        var event=store.optional("SELECT * FROM outbox_event WHERE id=?",id);
        if(event==null) { throw new ApiFailure(404,"RESOURCE_NOT_FOUND"); }
        if("PAYMENT_RESULT_RECORDED".equals(event.get("event_type"))) {
            long attempt=number(event,"payment_attempt_id");
            return localReceipt(payments.recover(new Operator(operator,Set.of(attempt)),attempt,key,reason));
        }
        return tx.execute(status -> {
            Long request=null;
            if("BOOKING_REQUESTED".equals(event.get("event_type"))) {
                request=number(event,"booking_request_id");
                var route=store.required("SELECT * FROM booking_request WHERE id=?",request);
                store.required("SELECT id FROM event_session WHERE id=? FOR SHARE",number(route,"session_id"));
                store.required("SELECT id FROM ticket_tier WHERE id=? FOR UPDATE",number(route,"tier_id"));
                store.optional("SELECT * FROM user_session_quota WHERE user_id=? AND session_id=? FOR UPDATE",number(route,"user_id"),number(route,"session_id"));
                store.required("SELECT * FROM booking_request WHERE id=? FOR UPDATE",request);
            }
            var locked=store.required("SELECT * FROM outbox_event WHERE id=? FOR UPDATE",id);
            var previous=store.optional("SELECT event_id,reason FROM processing_retry WHERE operator_id=? AND idempotency_key=?",operator,key);
            if(previous!=null) {
                if(number(previous,"event_id")!=id || !reason.equals(previous.get("reason"))) { throw new ApiFailure(409,"IDEMPOTENCY_CONFLICT"); }
                return Map.of("eventId",eventId,"accepted",true);
            }
            if(!"DEAD".equals(locked.get("state"))) { throw new ApiFailure(409,"NOT_RECOVERABLE"); }
            if(request!=null) {
                int changed=store.jdbc().update("UPDATE booking_request SET status='ACCEPTED',reason_code=NULL,finished_at=NULL WHERE id=? AND status='PROCESSING_FAILED' AND process_deadline>UTC_TIMESTAMP(3)",request);
                if(changed!=1) { throw new ApiFailure(409,"PROCESS_DEADLINE_EXCEEDED"); }
            } else if(!"CATALOG_INVALIDATE".equals(locked.get("event_type"))) { throw new ApiFailure(409,"NOT_RECOVERABLE"); }
            one(store.jdbc().update("UPDATE outbox_event SET state='READY',attempts=0,last_error_code=NULL,finished_at=NULL,next_attempt_at=UTC_TIMESTAMP(3),recovery_generation=recovery_generation+1,lease_token=NULL,lease_until=NULL,published_at=NULL WHERE id=? AND state='DEAD'",id));
            store.insert("INSERT INTO processing_retry(event_id,operator_id,reason,idempotency_key) VALUES(?,?,?,?)",id,operator,reason,key);
            return Map.of("eventId",eventId,"accepted",true);
        });
    }
    @GetMapping("/operations/{operationId}")
    public Map<String,Object> operation(HttpServletRequest r,@PathVariable String operationId) {
        long operator=actor(r);auth.requireAdmin(operator);
        if(!operationId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new ApiFailure(400,"INVALID_ARGUMENT");
        }
        var row=store.optional("SELECT attempt_id FROM sim_payment_operation WHERE operation_id=?",operationId);
        if(row==null) { throw new ApiFailure(404,"RESOURCE_NOT_FOUND"); }
        return localReceipt(payments.query(new Operator(operator,Set.of(number(row,"attempt_id"))),operationId));
    }
    private static Map<String,Object> localReceipt(Map<String,Object> original) {
        var receipt=new LinkedHashMap<>(original);
        receipt.put("statusUrl","/v1/admin/processing-failures/operations/"+original.get("operationId"));
        return receipt;
    }
    private static long actor(HttpServletRequest r) { return (Long)r.getAttribute("booking.principal"); }
}
