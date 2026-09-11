package com.ticketing.booking.domain;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

public final class Values {
    private static final DateTimeFormatter MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private Values() { }
    public static long id(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) { throw new ApiFailure(400,"INVALID_ARGUMENT"); }
        try { return Long.parseLong(value); } catch (NumberFormatException ex) { throw new ApiFailure(400,"INVALID_ARGUMENT"); }
    }
    public static long number(Map<String,Object> row, String name) { return ((Number)row.get(name)).longValue(); }
    public static Instant instant(Object value) {
        if (value instanceof Timestamp t) { return t.toInstant(); }
        if (value instanceof LocalDateTime t) { return t.toInstant(ZoneOffset.UTC); }
        if (value instanceof Instant t) { return t; }
        throw new IllegalStateException("Invalid database timestamp");
    }
    public static String time(Object value) { return MILLIS.format(instant(value)); }
    public static Timestamp sqlTime(Instant value) { return Timestamp.from(value); }
    public static Instant earlier(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
    public static void one(int affected) { if (affected != 1) { throw new IllegalStateException("ROW_COUNT_MISMATCH"); } }
}
