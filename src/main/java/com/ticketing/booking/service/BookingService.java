package com.ticketing.booking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.booking.config.RuntimeServices;
import com.ticketing.booking.domain.ApiFailure;
import com.ticketing.booking.persistence.BookingStore;
import static com.ticketing.booking.domain.Values.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class BookingService {
    private final BookingStore store;
    private final RuntimeServices runtime;
    private final EntryLimiter limiter;
    private final AllocationService allocation;
    private final TransactionTemplate tx;
    private final ObjectMapper json = new ObjectMapper();

    public BookingService(BookingStore store, RuntimeServices runtime, EntryLimiter limiter,
            AllocationService allocation, PlatformTransactionManager manager) {
        this.store=store; this.runtime=runtime; this.limiter=limiter; this.allocation=allocation;
        tx=new TransactionTemplate(manager); tx.setIsolationLevel(2); tx.setTimeout(8);
    }

    public Map<String,Object> accept(long user, long session, long tier, String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{8,64}")) { throw new ApiFailure(400,"INVALID_ARGUMENT"); }
        byte[] hash = hash(user,session,tier);
        var prior = existing(user,key,hash);
        if (prior != null) { return prior; }
        runtime.at("AFTER_IDEMPOTENCY_MISS",0);
        Map<String,Object> accepted;
        try {
            if(runtime.productEnabled() && store.jdbc().queryForObject("SELECT COUNT(*) FROM booking_request WHERE status='ACCEPTED'",Long.class)>=1000) {
                throw new ApiFailure(503,"BOOKING_BACKLOG_BUSY",3);
            }
            var catalogue = store.optional("SELECT s.* FROM event_session s JOIN ticket_tier t ON t.session_id=s.id WHERE s.id=? AND t.id=?",session,tier);
            if (catalogue == null) { throw new ApiFailure(404,"RESOURCE_NOT_FOUND"); }
            Instant now = runtime.afterLocks(store.jdbc());
            window(catalogue,now);
            limiter.check(user,tier,now);
            runtime.at("BEFORE_T1",0);
            accepted = tx.execute(status -> acceptTransaction(user,session,tier,key,hash));
        } catch (ApiFailure failure) {
            // Controller clarification: only committed facts can override a first-request rejection.
            var visible = existing(user,key,hash);
            if (visible != null) { return visible; }
            throw failure;
        } catch (DuplicateKeyException duplicate) {
            String detail = duplicate.getMostSpecificCause().getMessage();
            if (detail != null && detail.contains("uq_request_idempotency")) {
                var visible = existing(user,key,hash);
                if (visible != null) { return visible; }
            }
            throw new ApiFailure(503,"DEPENDENCY_UNAVAILABLE");
        } catch (TransactionException unknownCommit) {
            throw new ApiFailure(503,"ACCEPTANCE_UNKNOWN").causedBy(unknownCommit);
        } catch (DataAccessException failure) {
            throw new ApiFailure(503,"DEPENDENCY_UNAVAILABLE").causedBy(failure);
        }
        long requestId = Long.parseLong((String)accepted.get("requestId"));
        runtime.at("T1_COMMITTED",requestId);
        if (runtime.directEnabled()) { allocation.process(requestId); }
        runtime.at("BEFORE_ACCEPT_RESPONSE",requestId);
        return accepted;
    }

    private Map<String,Object> acceptTransaction(long user, long session, long tier, String key, byte[] hash) {
        var lockedSession = store.required("SELECT * FROM event_session WHERE id=? FOR SHARE",session);
        store.required("SELECT * FROM ticket_tier WHERE id=? AND session_id=? FOR UPDATE",tier,session);
        Instant acceptedAt = runtime.afterLocks(store.jdbc());
        window(lockedSession,acceptedAt);
        Instant deadline=earlier(acceptedAt.plusSeconds(120),instant(lockedSession.get("booking_close_at")));
        long request=store.insert("INSERT INTO booking_request(user_id,session_id,tier_id,quantity,idempotency_key,payload_hash,status,accepted_at,process_deadline) VALUES(?,?,?,1,?,?,'ACCEPTED',?,?)",
            user,session,tier,key,hash,sqlTime(acceptedAt),sqlTime(deadline));
        long event=store.insert("INSERT INTO outbox_event(event_type,aggregate_id,booking_request_id,payload,next_attempt_at,created_at) VALUES('BOOKING_REQUESTED',?,?,JSON_OBJECT(),?,?)",
            request,request,sqlTime(acceptedAt),sqlTime(acceptedAt));
        try {
            String payload=json.writeValueAsString(Map.of("schemaVersion",1,"eventId",Long.toString(event),
                "eventType","BOOKING_REQUESTED","requestId",Long.toString(request),"createdAt",time(acceptedAt),"traceId",UUID.randomUUID().toString()));
            one(store.jdbc().update("UPDATE outbox_event SET payload=? WHERE id=?",payload,event));
        } catch (java.io.IOException failure) { throw new IllegalStateException("EVENT_SERIALIZATION"); }
        runtime.at("T1_BEFORE_COMMIT",request);
        return envelope(request,acceptedAt,deadline);
    }

    private Map<String,Object> existing(long user,String key,byte[] hash) {
        var prior=store.optional("SELECT * FROM booking_request WHERE user_id=? AND idempotency_key=?",user,key);
        if (prior == null) { return null; }
        if (!MessageDigest.isEqual(hash,(byte[])prior.get("payload_hash"))) { throw new ApiFailure(409,"IDEMPOTENCY_CONFLICT"); }
        return envelope(number(prior,"id"),instant(prior.get("accepted_at")),instant(prior.get("process_deadline")));
    }
    private static Map<String,Object> envelope(long request,Instant accepted,Instant deadline) {
        return Map.of("requestId",Long.toString(request),"statusUrl","/v1/booking-requests/"+request,
            "acceptedAt",time(accepted),"processDeadline",time(deadline));
    }
    private static byte[] hash(long user,long session,long tier) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(("v1|booking|user="+user+"|session="+session+"|tier="+tier+"|quantity=1").getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void window(Map<String,Object> session,Instant now) {
        if (!"OPEN".equals(session.get("state")) || now.isBefore(instant(session.get("booking_open_at")))
                || !now.isBefore(instant(session.get("booking_close_at")))) { throw new ApiFailure(409,"SESSION_NOT_OPEN"); }
    }
    public Map<String,Object> query(long user,long request) {
        String refunds=runtime.productEnabled()?",o.current_refund_id,o.refunded_at,s.start_at AS refund_deadline":"";
        var r=store.optional("SELECT r.*, o.id AS actual_order_id,o.status AS order_status,o.unit_price_cent,o.quantity AS order_quantity,o.payable_cent,o.currency,o.hold_created_at,o.pay_expires_at,o.closed_at,o.close_reason,o.current_attempt_id,o.paid_attempt_id,o.paid_at"+refunds+" FROM booking_request r LEFT JOIN booking_order o ON o.request_id=r.id AND o.user_id=r.user_id JOIN event_session s ON s.id=r.session_id WHERE r.id=? AND r.user_id=?",request,user);
        if (r == null) { throw new ApiFailure(404,"RESOURCE_NOT_FOUND"); }
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("requestId",Long.toString(request)); result.put("status",r.get("status"));
        result.put("reasonCode",r.get("reason_code")); result.put("acceptedAt",time(r.get("accepted_at")));
        result.put("processDeadline",time(r.get("process_deadline"))); result.put("serverTime",time(runtime.afterLocks(store.jdbc())));
        result.put("order",null);
        if ("SUCCEEDED".equals(r.get("status"))) {
            if (r.get("actual_order_id")==null || number(r,"order_id")!=number(r,"actual_order_id")) { throw new IllegalStateException("ORDER_ASSOCIATION"); }
            Map<String,Object> order = new LinkedHashMap<>(Map.of("orderId",Long.toString(number(r,"actual_order_id")),"orderStatus",r.get("order_status"),
                "unitPriceCent",r.get("unit_price_cent"),"quantity",r.get("order_quantity"),"payableCent",r.get("payable_cent"),
                "currency",r.get("currency"),"holdCreatedAt",time(r.get("hold_created_at")),"payExpiresAt",time(r.get("pay_expires_at"))));
            order.put("closedAt",r.get("closed_at")==null?null:time(r.get("closed_at")));
            order.put("closeReason",r.get("close_reason"));
            OrderCloseService.paymentLinks(r,order);
            result.put("order",order);
        }
        return result;
    }
}
