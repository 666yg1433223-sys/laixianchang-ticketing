package com.ticketing.booking.model.po;

import java.time.Instant;

public record SessionPO(long id, long eventId, String state, Instant startAt, Instant bookingOpenAt, Instant bookingCloseAt) { }
