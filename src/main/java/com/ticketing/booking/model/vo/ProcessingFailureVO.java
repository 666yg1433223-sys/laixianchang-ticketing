package com.ticketing.booking.model.vo;

public record ProcessingFailureVO(String eventId, String eventType, String aggregateId,
        int attempts, String lastError) { }
