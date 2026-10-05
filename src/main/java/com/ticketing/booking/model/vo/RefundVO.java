package com.ticketing.booking.model.vo;


/** Explicit VO contract for the simulated payment/refund flow. */
public record RefundVO(
    String refundId,
    String orderId,
    String statusUrl,
    String status,
    boolean simulated,
    int amountCent,
    String currency,
    String reason,
    String orderStatus,
    String reviewReason,
    int attempts,
    long recoveryGeneration,
    String lastError,
    String requestedAt,
    String completedAt) {
    public RefundVO withStatusUrl(String statusUrl) {
        return new RefundVO(refundId,orderId,statusUrl,status,simulated,amountCent,currency,reason,orderStatus,reviewReason,attempts,recoveryGeneration,lastError,requestedAt,completedAt);
    }
}
