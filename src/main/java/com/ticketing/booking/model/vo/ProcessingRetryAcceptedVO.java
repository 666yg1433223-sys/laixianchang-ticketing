package com.ticketing.booking.model.vo;

public record ProcessingRetryAcceptedVO(String eventId, boolean accepted) implements ProcessingRetryResultVO { }
