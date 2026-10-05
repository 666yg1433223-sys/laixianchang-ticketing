package com.ticketing.booking.model.po;

/** Persisted catalogue mutation joined to its current outbox delivery generation. */
public record CatalogueInvalidationPO(long id, long attempts, long recoveryGeneration, long eventId, Long sessionId) { }
