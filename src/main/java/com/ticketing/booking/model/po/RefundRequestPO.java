package com.ticketing.booking.model.po;

import java.time.Instant;

/** Explicit PO contract for the simulated payment/refund flow. */
public record RefundRequestPO(
    long id,
    long orderId,
    long userId,
    long sessionId,
    long paymentAttemptId,
    String idempotencyKey,
    int amountCent,
    String currency,
    String reason,
    String status,
    Instant requestedAt,
    Instant completedAt,
    Instant nextAttemptAt,
    int attempts,
    long recoveryGeneration,
    String reviewReason,
    String lastErrorCode) {

}
