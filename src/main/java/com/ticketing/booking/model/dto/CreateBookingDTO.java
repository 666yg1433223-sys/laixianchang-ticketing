package com.ticketing.booking.model.dto;

/** Validated command; HTTP parsing remains strict in the controller. */
public record CreateBookingDTO(long userId,long sessionId,long tierId,String idempotencyKey) { }
