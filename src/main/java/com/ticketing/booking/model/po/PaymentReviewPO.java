package com.ticketing.booking.model.po;


/** Explicit PO contract for the simulated payment/refund flow. */
public record PaymentReviewPO(
    long id,
    long orderId,
    String resultId,
    String resultStatus,
    String settlementStatus,
    String reviewReason,
    int attempts,
    String lastErrorCode,
    long recoveryGeneration) {

}
