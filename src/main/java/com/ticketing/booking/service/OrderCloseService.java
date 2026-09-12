package com.ticketing.booking.service;

import com.ticketing.booking.config.RuntimeServices;
import com.ticketing.booking.domain.ApiFailure;
import com.ticketing.booking.persistence.BookingStore;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.ticketing.booking.domain.Values.*;

/** One atomic close operation, shared by authenticated cancellation and expiry recovery. */
@Service
public class OrderCloseService {
    private final BookingStore store;
    private final RuntimeServices runtime;
    private final TransactionTemplate tx;

    public OrderCloseService(BookingStore store, RuntimeServices runtime, PlatformTransactionManager manager) {
        this.store = store;
        this.runtime = runtime;
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(2);
        tx.setTimeout(8);
    }

    public Map<String, Object> query(long user, long orderId) {
        var order = store.optional("SELECT * FROM booking_order WHERE id=? AND user_id=?", orderId, user);
        if (order == null) { throw new ApiFailure(404, "RESOURCE_NOT_FOUND"); }
        return view(order);
    }

    public Map<String, Object> cancel(long user, long orderId) {
        // Owner-qualified route lookup also makes another user's order indistinguishable from absence.
        query(user, orderId);
        try {
            runtime.at("BEFORE_CLOSE", orderId);
            var result = tx.execute(status -> closeInTransaction(orderId, user, true));
            runtime.at("CLOSE_COMMITTED", orderId);
            runtime.at("BEFORE_CANCEL_RESPONSE", orderId);
            return result;
        } catch (ApiFailure failure) {
            throw failure;
        } catch (RuntimeException uncertain) {
            // Never equate a connection/commit acknowledgement failure with definite rollback.
            throw new ApiFailure(503, "CLOSE_RESULT_UNKNOWN").causedBy(uncertain);
        }
    }

    public void expire(long orderId) {
        runtime.at("BEFORE_CLOSE", orderId);
        tx.executeWithoutResult(status -> closeInTransaction(orderId, null, false));
        runtime.at("CLOSE_COMMITTED", orderId);
    }

    /** Must be invoked inside the caller's transaction, before taking any reverse-order business locks. */
    public Map<String, Object> closeInTransaction(long orderId, Long actor, boolean cancelRequested) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("CLOSE_REQUIRES_TRANSACTION");
        }
        if (cancelRequested && actor == null) { throw new IllegalArgumentException("CANCEL_REQUIRES_ACTOR"); }
        var locked = lockOrder(orderId, actor);
        String state = (String) locked.order().get("status");
        if ("CANCELLED".equals(state) || "EXPIRED".equals(state)) { return view(locked.order()); }
        runtime.at("CLOSE_LOCKS_ACQUIRED", orderId);
        return closeLocked(locked, runtime.afterLocks(store.jdbc()), cancelRequested);
    }

    public record LockedOrder(Map<String,Object> inventory, Map<String,Object> quota,
            Map<String,Object> request, Map<String,Object> order) { }

    /** Acquires the common business prefix only; caller takes attempt/event locks next, then reads time. */
    public LockedOrder lockOrder(long orderId, Long actor) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("ORDER_LOCK_REQUIRES_TX"); }
        var route = store.required("SELECT * FROM booking_order WHERE id=?", orderId);
        long user = number(route, "user_id"), session = number(route, "session_id"), tier = number(route, "tier_id");
        store.required("SELECT id FROM event_session WHERE id=? FOR SHARE", session);
        runtime.at("CLOSE_SESSION_LOCKED", orderId);
        var inventory = store.required("SELECT * FROM ticket_tier WHERE id=? AND session_id=? FOR UPDATE", tier, session);
        var quota = store.optional("SELECT * FROM user_session_quota WHERE user_id=? AND session_id=? FOR UPDATE", user, session);
        var request = route.get("request_id") != null
            ? store.required("SELECT * FROM booking_request WHERE id=? FOR UPDATE", number(route,"request_id"))
            : store.required("SELECT * FROM waitlist_entry WHERE id=? FOR UPDATE", number(route,"waitlist_entry_id"));
        var order = store.required("SELECT * FROM booking_order WHERE id=? FOR UPDATE", orderId);
        if (number(order, "user_id") != user || number(order, "session_id") != session
                || number(order, "tier_id") != tier || !java.util.Objects.equals(order.get("request_id"),route.get("request_id"))
                || !java.util.Objects.equals(order.get("waitlist_entry_id"),route.get("waitlist_entry_id"))) {
            throw new IllegalStateException("CLOSE_IMMUTABLE_ROUTE_CHANGED");
        }
        if (actor != null && actor != user) { throw new ApiFailure(404, "RESOURCE_NOT_FOUND"); }
        return new LockedOrder(inventory, quota, request, order);
    }

    /** Already-locked core: never reacquire the business prefix after an attempt/outbox lock. */
    public Map<String,Object> closeLocked(LockedOrder locked, Instant now, boolean cancelRequested) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("CLOSE_REQUIRES_TRANSACTION"); }
        var order=locked.order(); var inventory=locked.inventory(); var quota=locked.quota(); var request=locked.request();
        long orderId=number(order,"id"), user=number(order,"user_id"), session=number(order,"session_id"), tier=number(order,"tier_id");
        String state=(String)order.get("status");
        if ("CANCELLED".equals(state) || "EXPIRED".equals(state)) { return view(order); }
        if ("PAID".equals(state)) {
            if (cancelRequested) { throw new ApiFailure(409,"PAID_CANCEL_DISABLED"); }
            return view(order);
        }
        if (!"HOLD".equals(state)) { throw new IllegalStateException("CLOSE_UNSUPPORTED_STATE"); }
        Instant deadline = instant(order.get("pay_expires_at"));
        if (!cancelRequested && now.isBefore(deadline)) { return view(order); }
        if (!validSource(locked)
                || quota == null || !"ORDER".equals(quota.get("owner_type")) || number(quota, "owner_id") != orderId) {
            throw new IllegalStateException("CLOSE_OWNER_OR_REQUEST_INCONSISTENT");
        }
        var ledger = store.jdbc().queryForList("SELECT action,delta,tier_id FROM inventory_ledger WHERE order_id=?", orderId);
        if (ledger.size() != 1 || !"RESERVE".equals(ledger.get(0).get("action"))
                || number(ledger.get(0), "delta") != -1 || number(ledger.get(0), "tier_id") != tier) {
            throw new IllegalStateException("CLOSE_LEDGER_INCONSISTENT");
        }
        String terminal = now.isBefore(deadline) ? "CANCELLED" : "EXPIRED";
        String reason = terminal.equals("CANCELLED") ? "USER_CANCELLED" : "PAYMENT_DEADLINE_EXCEEDED";
        one(store.jdbc().update("UPDATE booking_order SET status=?,closed_at=?,close_reason=? WHERE id=? AND status='HOLD'",
                terminal, sqlTime(now), reason, orderId));
        runtime.at("CLOSE_AFTER_STATUS", orderId);
        one(store.jdbc().update("UPDATE ticket_tier SET available=available+1 WHERE id=? AND available<capacity", tier));
        runtime.at("CLOSE_AFTER_STOCK", orderId);
        one(store.jdbc().update("UPDATE user_session_quota SET owner_type='NONE',owner_id=NULL,updated_at=? WHERE user_id=? AND session_id=? AND owner_type='ORDER' AND owner_id=?",
                sqlTime(now), user, session, orderId));
        runtime.at("CLOSE_AFTER_QUOTA", orderId);
        store.insert("INSERT INTO inventory_ledger(tier_id,order_id,action,delta,available_after,created_at) VALUES(?,?,'RELEASE',1,?,?)",
                tier, orderId, number(inventory, "available") + 1, sqlTime(now));
        runtime.at("CLOSE_AFTER_LEDGER", orderId);
        order.put("status", terminal);
        order.put("closed_at", sqlTime(now));
        order.put("close_reason", reason);
        runtime.at("CLOSE_BEFORE_COMMIT", orderId);
        return view(order);
    }

    private static Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (var field : Map.of("orderId", "id", "requestId", "request_id", "sessionId", "session_id", "tierId", "tier_id").entrySet()) {
            result.put(field.getKey(), row.get(field.getValue()) == null ? null : Long.toString(number(row, field.getValue())));
        }
        if (row.containsKey("waitlist_entry_id")) {
            result.put("waitlistId",row.get("waitlist_entry_id")==null?null:Long.toString(number(row,"waitlist_entry_id")));
        }
        result.put("orderStatus", row.get("status"));
        result.put("unitPriceCent", row.get("unit_price_cent"));
        result.put("quantity", row.get("quantity"));
        result.put("payableCent", row.get("payable_cent"));
        result.put("currency", row.get("currency"));
        result.put("holdCreatedAt", time(row.get("hold_created_at")));
        result.put("payExpiresAt", time(row.get("pay_expires_at")));
        result.put("closedAt", row.get("closed_at") == null ? null : time(row.get("closed_at")));
        result.put("closeReason", row.get("close_reason"));
        paymentLinks(row,result);
        return result;
    }

    public static void paymentLinks(Map<String,Object> row, Map<String,Object> result) {
        result.put("currentAttemptId",row.get("current_attempt_id")==null?null:Long.toString(number(row,"current_attempt_id")));
        result.put("paidAttemptId",row.get("paid_attempt_id")==null?null:Long.toString(number(row,"paid_attempt_id")));
        result.put("paidAt",row.get("paid_at")==null?null:time(row.get("paid_at")));
    }

    public static boolean validSource(LockedOrder locked) {
        var source=locked.request(); var order=locked.order();
        String expected=order.get("request_id")==null?"ALLOCATED":"SUCCEEDED";
        return source!=null && expected.equals(source.get("status")) && source.get("order_id")!=null
            && number(source,"order_id")==number(order,"id") && number(source,"user_id")==number(order,"user_id")
            && number(source,"session_id")==number(order,"session_id") && number(source,"tier_id")==number(order,"tier_id");
    }
}
