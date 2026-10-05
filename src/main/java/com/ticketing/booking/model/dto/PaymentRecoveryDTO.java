package com.ticketing.booking.model.dto;


/** Explicit DTO contract for the simulated payment/refund flow. */
public record PaymentRecoveryDTO(
    long attemptId,
    String idempotencyKey,
    String reason) {

}
