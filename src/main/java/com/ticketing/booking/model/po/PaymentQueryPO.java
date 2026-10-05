package com.ticketing.booking.model.po;

import java.time.Instant;

/** Explicit PO contract for the simulated payment/refund flow. */
public record PaymentQueryPO(
    PaymentAttemptPO attempt,
    String orderStatus,
    Long currentAttemptId,
    Long paidAttemptId,
    Instant paidAt,
    boolean refundFields,
    Long currentRefundId,
    Instant refundedAt,
    Instant refundDeadline) {

}
