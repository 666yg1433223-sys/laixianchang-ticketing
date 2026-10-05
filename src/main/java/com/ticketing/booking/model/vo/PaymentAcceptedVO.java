package com.ticketing.booking.model.vo;


/** Explicit VO contract for the simulated payment/refund flow. */
public record PaymentAcceptedVO(
    String attemptId,
    String orderId,
    String statusUrl,
    boolean simulated,
    String message) {

}
