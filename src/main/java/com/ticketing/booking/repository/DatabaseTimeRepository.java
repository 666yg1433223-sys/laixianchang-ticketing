package com.ticketing.booking.repository;

import com.ticketing.booking.domain.DatabaseTimeSource;
import com.ticketing.booking.domain.Values;
import java.time.Instant;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Database time is read after row locks, rather than using a potentially stale JVM clock. */
@Repository
public class DatabaseTimeRepository implements DatabaseTimeSource {
    private final JdbcTemplate jdbc;

    public DatabaseTimeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Instant now() {
        return Values.instant(jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)", LocalDateTime.class));
    }
}
