package com.ticketing.booking.model.dto;

/** Authenticated user is supplied by the server, never trusted from request JSON. */
public record JoinWaitlistDTO(long userId,long sessionId,long tierId,String idempotencyKey) { }
