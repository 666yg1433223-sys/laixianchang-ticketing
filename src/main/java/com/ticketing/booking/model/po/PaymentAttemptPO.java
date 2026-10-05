package com.ticketing.booking.model.po;

import java.time.Instant;

/** Explicit PO contract for the simulated payment/refund flow. */
public record PaymentAttemptPO(
    long id,
    long orderId,
    long userId,
    long sessionId,
    String idempotencyKey,
    int attemptNo,
    int amountCent,
    String currency,
    Instant createdAt,
    String plannedStatus,
    Instant plannedAt,
    Instant resolutionDeadline,
    String resultStatus,
    String resultId,
    Instant resultAt,
    String resultReason,
    byte[] resultDigest,
    String settlementStatus,
    Instant settledAt,
    String reviewReason,
    String compensationId,
    Integer compensationCent,
    Instant compensationAt) {
    public PaymentAttemptPO withResult(String status, String resultId, Instant resultAt, String reason) {
        return new PaymentAttemptPO(id,orderId,userId,sessionId,idempotencyKey,attemptNo,amountCent,currency,createdAt,
            plannedStatus,plannedAt,resolutionDeadline,status,resultId,resultAt,reason,resultDigest,settlementStatus,
            settledAt,reviewReason,compensationId,compensationCent,compensationAt);
    }
}
