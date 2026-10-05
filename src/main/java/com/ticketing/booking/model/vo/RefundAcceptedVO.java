package com.ticketing.booking.model.vo;


/** Explicit VO contract for the simulated payment/refund flow. */
public record RefundAcceptedVO(
    String refundId,
    String orderId,
    String statusUrl,
    String status,
    boolean simulated) {
    public RefundAcceptedVO withStatusUrl(String statusUrl) {
        return new RefundAcceptedVO(refundId,orderId,statusUrl,status,simulated);
    }
}
