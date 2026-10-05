package com.ticketing.booking.domain;

import java.time.Instant;

public interface DatabaseClock {
    Instant afterLocks(DatabaseTimeSource databaseTime);
}
