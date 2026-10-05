package com.ticketing.booking.model.dto;

public record ProcessingRetryDTO(long eventId, String idempotencyKey, String reason) { }
