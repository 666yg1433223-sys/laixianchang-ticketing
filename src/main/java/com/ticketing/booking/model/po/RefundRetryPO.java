package com.ticketing.booking.model.po;

import java.time.Instant;

/** Explicit PO contract for the simulated payment/refund flow. */
public record RefundRetryPO(
    long id,
    long refundId,
    long operatorId,
    String idempotencyKey,
    String reason,
    long generation,
    Instant createdAt) {

}
