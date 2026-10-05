package com.ticketing.booking.model.dto;

import java.time.Instant;

/** Validated UTC session fields; omitted patch properties remain null. */
public record CatalogueSessionDTO(Long eventId, Instant startAt, Instant bookingOpenAt, Instant bookingCloseAt) { }
