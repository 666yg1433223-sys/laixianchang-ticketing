package com.ticketing.booking.model.po;

import java.time.Instant;

/** Explicit PO contract for the simulated payment/refund flow. */
public record PaymentResultEventPO(
    long id,
    long aggregateId,
    Long paymentAttemptId,
    String state,
    long recoveryGeneration,
    int attempts,
    Instant nextAttemptAt,
    String lastErrorCode) {

}
