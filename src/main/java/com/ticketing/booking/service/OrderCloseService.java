package com.ticketing.booking.service;

import com.ticketing.booking.model.vo.OrderVO;
import java.time.Instant;
import java.util.Map;
import static com.ticketing.booking.domain.Values.*;

public interface OrderCloseService {
    OrderVO query(long user,long orderId);
    OrderVO cancel(long user,long orderId);
    void expire(long orderId);
    Map<String,Object> closeInTransaction(long orderId,Long actor,boolean cancelRequested);
    LockedOrder lockOrder(long orderId,Long actor);
    Map<String,Object> closeLocked(LockedOrder locked,Instant now,boolean cancelRequested);

    /** Shared in-transaction protocol retained for payment/refund; rows originate in typed repositories. */
    record LockedOrder(Map<String,Object> inventory,Map<String,Object> quota,Map<String,Object> request,Map<String,Object> order) { }
    static void paymentLinks(Map<String,Object> row,Map<String,Object> result) {
        result.put("currentAttemptId",row.get("current_attempt_id")==null?null:Long.toString(number(row,"current_attempt_id")));
        result.put("paidAttemptId",row.get("paid_attempt_id")==null?null:Long.toString(number(row,"paid_attempt_id")));
        result.put("paidAt",row.get("paid_at")==null?null:time(row.get("paid_at")));refundLinks(row,result);
    }
    static void refundLinks(Map<String,Object> row,Map<String,Object> result) {
        if(!row.containsKey("current_refund_id")) { return; }
        result.put("currentRefundId",row.get("current_refund_id")==null?null:Long.toString(number(row,"current_refund_id")));
        result.put("refundedAt",row.get("refunded_at")==null?null:time(row.get("refunded_at")));
        if(row.get("refund_deadline")!=null) { result.put("refundDeadline",time(row.get("refund_deadline"))); }
    }
    static boolean validSource(LockedOrder locked) {
        var source=locked.request();var order=locked.order();String expected=order.get("request_id")==null?"ALLOCATED":"SUCCEEDED";
        return source!=null && expected.equals(source.get("status")) && source.get("order_id")!=null && number(source,"order_id")==number(order,"id")
            && number(source,"user_id")==number(order,"user_id") && number(source,"session_id")==number(order,"session_id") && number(source,"tier_id")==number(order,"tier_id");
    }
}
