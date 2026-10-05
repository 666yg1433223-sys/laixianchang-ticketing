package com.ticketing.booking.model.dto;


/** Explicit DTO contract for the simulated payment/refund flow. */
public record PaymentAcceptDTO(
    long userId,
    long orderId,
    String idempotencyKey) {

}
