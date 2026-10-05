package com.ticketing.booking.model.po;

import java.time.Instant;

/** Explicit PO contract for the simulated payment/refund flow. */
public record RefundGenerationPO(
    long recoveryGeneration,
    int attempts,
    Instant nextAttemptAt) {

}
