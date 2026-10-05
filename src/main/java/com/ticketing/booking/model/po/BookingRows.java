package com.ticketing.booking.model.po;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Database records used by booking, allocation, closing and waitlist operations. */
public final class BookingRows {
    private BookingRows() { }
    public record Session(long id, String state, Instant opensAt, Instant closesAt) { }
    public record Tier(long id, long sessionId, int unitPriceCent, int available, int capacity) {
        public Map<String,Object> legacy() { return Map.of("id",id,"session_id",sessionId,"unit_price_cent",unitPriceCent,"available",available,"capacity",capacity); }
    }
    public record Quota(long userId,long sessionId,String ownerType,Long ownerId) {
        public Map<String,Object> legacy() { var row=new LinkedHashMap<String,Object>(); row.put("user_id",userId);row.put("session_id",sessionId);row.put("owner_type",ownerType);row.put("owner_id",ownerId);return row; }
    }
    public record Request(long id,long userId,long sessionId,long tierId,byte[] payloadHash,String status,
            String reasonCode,Instant acceptedAt,Instant processDeadline,Long orderId) {
        public Map<String,Object> legacy() { var row=new LinkedHashMap<String,Object>();row.put("id",id);row.put("user_id",userId);row.put("session_id",sessionId);row.put("tier_id",tierId);row.put("status",status);row.put("order_id",orderId);return row; }
    }
    public record Outbox(long id,String state,int attempts,Instant nextAttemptAt,long recoveryGeneration) { }
    public record Ledger(String action,long delta,long tierId) { }
    public record Waitlist(long id,long userId,long sessionId,long tierId,byte[] payloadHash,String status,
            Long orderId,Instant createdAt) {
        public Map<String,Object> legacy() { var row=new LinkedHashMap<String,Object>();row.put("id",id);row.put("user_id",userId);row.put("session_id",sessionId);row.put("tier_id",tierId);row.put("status",status);row.put("order_id",orderId);return row; }
    }
    public record OrderSummary(long id,long eventId,String title,Instant startsAt,String tierName) { }
    public record BookingStatus(Request request,Order order) { }

    /** Optional product columns are distinguished from SQL NULL for legacy fixture compatibility. */
    public record Order(long id,Long requestId,Long waitlistEntryId,long userId,long sessionId,long tierId,
            String status,int unitPriceCent,int quantity,int payableCent,String currency,Instant holdCreatedAt,
            Instant payExpiresAt,Instant closedAt,String closeReason,Long currentAttemptId,Long paidAttemptId,
            Instant paidAt,Long currentRefundId,Instant refundedAt,Instant refundDeadline,Instant refundClock,
            boolean waitlistColumn,boolean refundColumns) {
        /** Compatibility boundary for payment/refund's shared locked-order protocol. */
        public Map<String,Object> legacy() {
            var row=new LinkedHashMap<String,Object>();
            row.put("id",id);row.put("request_id",requestId);row.put("user_id",userId);row.put("session_id",sessionId);row.put("tier_id",tierId);
            if(waitlistColumn) { row.put("waitlist_entry_id",waitlistEntryId); }
            row.put("status",status);row.put("unit_price_cent",unitPriceCent);row.put("quantity",quantity);row.put("payable_cent",payableCent);row.put("currency",currency);
            row.put("hold_created_at",holdCreatedAt);row.put("pay_expires_at",payExpiresAt);row.put("closed_at",closedAt);row.put("close_reason",closeReason);
            row.put("current_attempt_id",currentAttemptId);row.put("paid_attempt_id",paidAttemptId);row.put("paid_at",paidAt);
            if(refundColumns) { row.put("current_refund_id",currentRefundId);row.put("refunded_at",refundedAt); }
            if(refundDeadline!=null) { row.put("refund_deadline",refundDeadline); }
            if(refundClock!=null) { row.put("refund_clock",refundClock); }
            return row;
        }
    }
}
