package com.ticketing.booking.domain;

import java.time.Instant;

/** Reads database time on the connection participating in the current transaction. */
@FunctionalInterface
public interface DatabaseTimeSource {
    Instant now();
}
