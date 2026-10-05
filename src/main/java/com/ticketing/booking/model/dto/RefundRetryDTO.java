package com.ticketing.booking.model.dto;


/** Explicit DTO contract for the simulated payment/refund flow. */
public record RefundRetryDTO(
    long operatorId,
    long refundId,
    String idempotencyKey,
    String reason) {

}
