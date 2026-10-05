package com.ticketing.booking.model.po;

import java.time.Instant;

/** Explicit PO contract for the simulated payment/refund flow. */
public record PaymentOperationPO(
    String operationId,
    long operatorId,
    String action,
    long attemptId,
    String targetId,
    String idempotencyKey,
    String reason,
    int deliveries,
    byte[] parameterDigest,
    String beforeSettlement,
    String beforeEvent,
    int beforeAttempts,
    String beforeError,
    long generation,
    Instant acceptedAt,
    Instant completedAt,
    String status,
    String result,
    String rejectionReason) {

}
