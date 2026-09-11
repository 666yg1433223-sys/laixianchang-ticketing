package com.ticketing.booking.domain;

import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;

public interface DatabaseClock {
    Instant afterLocks(JdbcTemplate jdbc);
}
