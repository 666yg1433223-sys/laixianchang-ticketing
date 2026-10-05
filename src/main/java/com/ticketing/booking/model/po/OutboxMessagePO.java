package com.ticketing.booking.model.po;

/** Persisted fields needed to route, publish, acknowledge and recover an outbox message. */
public record OutboxMessagePO(long id, String eventType, long aggregateId,
        Long bookingRequestId, Long paymentAttemptId, String payload, String state,
        int attempts, String lastError, long recoveryGeneration) { }
