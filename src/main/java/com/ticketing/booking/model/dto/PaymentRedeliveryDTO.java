package com.ticketing.booking.model.dto;


/** Explicit DTO contract for the simulated payment/refund flow. */
public record PaymentRedeliveryDTO(
    String resultId,
    String idempotencyKey,
    String reason,
    int deliveries) {

}
