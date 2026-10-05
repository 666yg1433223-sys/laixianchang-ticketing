package com.ticketing.booking.model.dto;


/** Explicit DTO contract for the simulated payment/refund flow. */
public record RefundAcceptDTO(
    long userId,
    long orderId,
    String idempotencyKey,
    String reason) {

}
