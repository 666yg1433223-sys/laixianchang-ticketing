package com.ticketing.booking.repository;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookingRowMappingOfflineTest {
    @Test void datetimeUsesUtcWithoutTheJvmDefaultTimeZone() throws Exception {
        var rs=mock(ResultSet.class);when(rs.getObject("created_at")).thenReturn(LocalDateTime.of(2030,1,1,10,0));
        assertEquals(Instant.parse("2030-01-01T10:00:00Z"),BookingJdbcSupport.timestamp(rs,"created_at"));
        when(rs.getObject("created_at")).thenReturn(null);assertNull(BookingJdbcSupport.timestamp(rs,"created_at"));
    }
    @Test void nullableForeignKeyRemainsNullInsteadOfZero() throws Exception {
        var rs=mock(ResultSet.class);when(rs.getLong("order_id")).thenReturn(0L);when(rs.wasNull()).thenReturn(true);
        assertNull(BookingJdbcSupport.nullableLong(rs,"order_id"));
        when(rs.getLong("order_id")).thenReturn(9007199254740993L);when(rs.wasNull()).thenReturn(false);
        assertEquals(9007199254740993L,BookingJdbcSupport.nullableLong(rs,"order_id"));
    }
}
