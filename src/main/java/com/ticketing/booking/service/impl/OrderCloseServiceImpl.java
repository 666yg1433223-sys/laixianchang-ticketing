package com.ticketing.booking.service.impl;

import com.ticketing.booking.config.RuntimeServices;
import com.ticketing.booking.domain.ApiFailure;
import com.ticketing.booking.model.vo.OrderVO;
import com.ticketing.booking.repository.OrderRepository;
import com.ticketing.booking.repository.DatabaseTimeRepository;
import com.ticketing.booking.service.OrderCloseService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.ticketing.booking.domain.Values.*;

/** Owns the shared close transaction; repositories only persist the chosen transition. */
@Service
public class OrderCloseServiceImpl implements OrderCloseService {
    private final OrderRepository repository;
    private final DatabaseTimeRepository clock;
    private final RuntimeServices runtime;
    private final TransactionTemplate tx;
    public OrderCloseServiceImpl(OrderRepository repository,DatabaseTimeRepository clock,RuntimeServices runtime,PlatformTransactionManager manager) {
        this.repository=repository;this.clock=clock;this.runtime=runtime;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(2);tx.setTimeout(8);
    }
    @Override public OrderVO query(long user,long orderId) {
        var order=repository.findOwned(orderId,user);if(order==null) { throw new ApiFailure(404,"RESOURCE_NOT_FOUND"); }
        Boolean eligible=order.refundColumns()?runtime.productEnabled() && runtime.simulatorEnabled() && "PAID".equals(order.status()) && order.refundClock().isBefore(order.refundDeadline()):null;
        return OrderVO.from(order,eligible);
    }
    @Override public OrderVO cancel(long user,long orderId) {
        query(user,orderId);
        try {
            runtime.at("BEFORE_CLOSE",orderId);
            var result=tx.execute(status->closeInTransaction(orderId,user,true));
            runtime.at("CLOSE_COMMITTED",orderId);runtime.at("BEFORE_CANCEL_RESPONSE",orderId);
            return fromCloseView(result);
        } catch(ApiFailure failure) { throw failure;
        } catch(RuntimeException uncertain) { throw new ApiFailure(503,"CLOSE_RESULT_UNKNOWN").causedBy(uncertain); }
    }
    @Override public void expire(long orderId) {
        runtime.at("BEFORE_CLOSE",orderId);tx.executeWithoutResult(status->closeInTransaction(orderId,null,false));runtime.at("CLOSE_COMMITTED",orderId);
    }
    @Override public Map<String,Object> closeInTransaction(long orderId,Long actor,boolean cancelRequested) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("CLOSE_REQUIRES_TRANSACTION"); }
        if(cancelRequested && actor==null) { throw new IllegalArgumentException("CANCEL_REQUIRES_ACTOR"); }
        var locked=lockOrder(orderId,actor);String state=(String)locked.order().get("status");
        if("CANCELLED".equals(state) || "EXPIRED".equals(state) || "REFUNDED".equals(state)) { return view(locked.order()); }
        runtime.at("CLOSE_LOCKS_ACQUIRED",orderId);return closeLocked(locked,runtime.afterLocks(clock),cancelRequested);
    }
    @Override public LockedOrder lockOrder(long orderId,Long actor) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("ORDER_LOCK_REQUIRES_TX"); }
        var route=repository.route(orderId);long user=route.userId(),session=route.sessionId(),tier=route.tierId();
        repository.lockSession(session);runtime.at("CLOSE_SESSION_LOCKED",orderId);
        var inventory=repository.lockTier(tier,session);var quota=repository.lockQuota(user,session);
        var request=route.requestId()!=null?repository.lockRequest(route.requestId()).legacy():repository.lockWaitlist(route.waitlistEntryId()).legacy();
        var order=repository.lockOrder(orderId);
        if(order.userId()!=user || order.sessionId()!=session || order.tierId()!=tier || !java.util.Objects.equals(order.requestId(),route.requestId()) || !java.util.Objects.equals(order.waitlistEntryId(),route.waitlistEntryId())) {
            throw new IllegalStateException("CLOSE_IMMUTABLE_ROUTE_CHANGED");
        }
        if(actor!=null && actor!=user) { throw new ApiFailure(404,"RESOURCE_NOT_FOUND"); }
        return new LockedOrder(inventory.legacy(),quota==null?null:quota.legacy(),request,order.legacy());
    }
    @Override public Map<String,Object> closeLocked(LockedOrder locked,Instant now,boolean cancelRequested) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("CLOSE_REQUIRES_TRANSACTION"); }
        var order=locked.order();var inventory=locked.inventory();var quota=locked.quota();
        long orderId=number(order,"id"),user=number(order,"user_id"),session=number(order,"session_id"),tier=number(order,"tier_id");
        String state=(String)order.get("status");
        if("CANCELLED".equals(state) || "EXPIRED".equals(state) || "REFUNDED".equals(state)) { return view(order); }
        if("REFUNDING".equals(state)) { if(cancelRequested) { throw new ApiFailure(409,"REFUND_IN_PROGRESS"); }return view(order); }
        if("PAID".equals(state)) { if(cancelRequested) { throw new ApiFailure(409,"PAID_CANCEL_DISABLED"); }return view(order); }
        if(!"HOLD".equals(state)) { throw new IllegalStateException("CLOSE_UNSUPPORTED_STATE"); }
        Instant deadline=instant(order.get("pay_expires_at"));if(!cancelRequested && now.isBefore(deadline)) { return view(order); }
        if(!OrderCloseService.validSource(locked) || quota==null || !"ORDER".equals(quota.get("owner_type")) || number(quota,"owner_id")!=orderId) { throw new IllegalStateException("CLOSE_OWNER_OR_REQUEST_INCONSISTENT"); }
        var ledger=repository.ledger(orderId);
        if(ledger.size()!=1 || !"RESERVE".equals(ledger.get(0).action()) || ledger.get(0).delta()!=-1 || ledger.get(0).tierId()!=tier) { throw new IllegalStateException("CLOSE_LEDGER_INCONSISTENT"); }
        String terminal=now.isBefore(deadline)?"CANCELLED":"EXPIRED",reason=terminal.equals("CANCELLED")?"USER_CANCELLED":"PAYMENT_DEADLINE_EXCEEDED";
        one(repository.closeHold(orderId,terminal,reason,now));runtime.at("CLOSE_AFTER_STATUS",orderId);
        one(repository.releaseTicket(tier));runtime.at("CLOSE_AFTER_STOCK",orderId);
        one(repository.releaseQuota(user,session,orderId,now));runtime.at("CLOSE_AFTER_QUOTA",orderId);
        repository.insertRelease(tier,orderId,number(inventory,"available")+1,now);runtime.at("CLOSE_AFTER_LEDGER",orderId);
        order.put("status",terminal);order.put("closed_at",sqlTime(now));order.put("close_reason",reason);
        runtime.at("CLOSE_BEFORE_COMMIT",orderId);return view(order);
    }
    /** Compatibility return for existing payment/refund coordination calls. HTTP returns typed OrderVO. */
    private static Map<String,Object> view(Map<String,Object> row) {
        var vo=OrderVO.fromLegacy(row,null);var result=new LinkedHashMap<String,Object>();
        result.put("orderId",vo.orderId());result.put("requestId",vo.requestId());result.put("sessionId",vo.sessionId());result.put("tierId",vo.tierId());
        result.put("orderStatus",vo.orderStatus());result.put("unitPriceCent",vo.unitPriceCent());result.put("quantity",vo.quantity());result.put("payableCent",vo.payableCent());result.put("currency",vo.currency());
        result.put("holdCreatedAt",vo.holdCreatedAt());result.put("payExpiresAt",vo.payExpiresAt());result.put("closedAt",vo.closedAt());result.put("closeReason",vo.closeReason());
        result.put("currentAttemptId",vo.currentAttemptId());result.put("paidAttemptId",vo.paidAttemptId());result.put("paidAt",vo.paidAt());result.putAll(vo.productFields());return result;
    }
    private static OrderVO fromCloseView(Map<String,Object> row) {
        var extra=new LinkedHashMap<String,Object>();for(String key:java.util.List.of("waitlistId","currentRefundId","refundedAt","refundDeadline")) { if(row.containsKey(key)) { extra.put(key,row.get(key)); } }
        return new OrderVO((String)row.get("orderId"),(String)row.get("requestId"),(String)row.get("sessionId"),(String)row.get("tierId"),(String)row.get("orderStatus"),
            ((Number)row.get("unitPriceCent")).intValue(),((Number)row.get("quantity")).intValue(),((Number)row.get("payableCent")).intValue(),(String)row.get("currency"),
            (String)row.get("holdCreatedAt"),(String)row.get("payExpiresAt"),(String)row.get("closedAt"),(String)row.get("closeReason"),(String)row.get("currentAttemptId"),(String)row.get("paidAttemptId"),(String)row.get("paidAt"),extra);
    }
}
