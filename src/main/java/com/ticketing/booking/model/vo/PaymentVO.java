package com.ticketing.booking.model.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

/** The optional refund extension is absent for the original isolated schema. */
public record PaymentVO(String attemptId, String orderId, String statusUrl, boolean simulated, String message,
    String resultStatus, String settlementStatus, int amountCent, String currency, String orderStatus,
    String resultId, String resultReason, String reviewReason, String compensationId, Integer compensationCent,
    String createdAt, String resolutionDeadline, String resultAt, String settledAt, String compensationAt,
    String currentAttemptId, String paidAttemptId, String paidAt,
    @JsonUnwrapped @JsonInclude(JsonInclude.Include.NON_NULL) RefundLinks refundLinks) {
    public record RefundLinks(
    String currentRefundId,
    String refundedAt,
    String refundDeadline) { }
}
