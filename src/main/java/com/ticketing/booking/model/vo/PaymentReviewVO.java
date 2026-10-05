package com.ticketing.booking.model.vo;


/** Explicit VO contract for the simulated payment/refund flow. */
public record PaymentReviewVO(
    String attemptId,
    String orderId,
    String resultId,
    String resultStatus,
    String settlementStatus,
    String eventState,
    String generation,
    long attempts,
    String reviewReason,
    String lastError) {

}
