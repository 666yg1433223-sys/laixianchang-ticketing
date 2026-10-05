package com.ticketing.booking.model.vo;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.ticketing.booking.model.po.BookingRows.Order;
import java.util.LinkedHashMap;
import java.util.Map;
import static com.ticketing.booking.domain.Values.*;

/** Stable HTTP order representation, independent of database column names. */
public record OrderVO(String orderId,String requestId,String sessionId,String tierId,String orderStatus,
        int unitPriceCent,int quantity,int payableCent,String currency,String holdCreatedAt,String payExpiresAt,
        String closedAt,String closeReason,String currentAttemptId,String paidAttemptId,String paidAt,
        @JsonAnyGetter Map<String,Object> productFields) {
    public static OrderVO from(Order row,Boolean refundEligible) { return fromLegacy(row.legacy(),refundEligible); }
    public static OrderVO fromLegacy(Map<String,Object> row,Boolean refundEligible) {
        var extra=productFields(row);
        if(row.containsKey("waitlist_entry_id")) { extra.put("waitlistId",identifier(row.get("waitlist_entry_id"))); }
        if(refundEligible!=null) { extra.put("refundEligible",refundEligible); }
        return new OrderVO(identifier(row.get("id")),identifier(row.get("request_id")),identifier(row.get("session_id")),identifier(row.get("tier_id")),
            (String)row.get("status"),((Number)row.get("unit_price_cent")).intValue(),((Number)row.get("quantity")).intValue(),((Number)row.get("payable_cent")).intValue(),
            (String)row.get("currency"),time(row.get("hold_created_at")),time(row.get("pay_expires_at")),timestamp(row.get("closed_at")),(String)row.get("close_reason"),
            identifier(row.get("current_attempt_id")),identifier(row.get("paid_attempt_id")),timestamp(row.get("paid_at")),extra);
    }
    public static Map<String,Object> productFields(Map<String,Object> row) {
        var result=new LinkedHashMap<String,Object>();
        if(row.containsKey("current_refund_id")) {
            result.put("currentRefundId",identifier(row.get("current_refund_id")));result.put("refundedAt",timestamp(row.get("refunded_at")));
            if(row.get("refund_deadline")!=null) { result.put("refundDeadline",time(row.get("refund_deadline"))); }
        }
        return result;
    }
    public static String identifier(Object value) { return value==null?null:Long.toString(((Number)value).longValue()); }
    public static String timestamp(Object value) { return value==null?null:time(value); }
}
