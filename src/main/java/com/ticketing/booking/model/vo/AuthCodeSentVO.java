package com.ticketing.booking.model.vo;

public record AuthCodeSentVO(boolean sent, long expiresInSeconds, long retryAfterSeconds, String delivery) { }
