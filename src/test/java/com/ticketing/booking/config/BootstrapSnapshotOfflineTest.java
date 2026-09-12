package com.ticketing.booking.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BootstrapSnapshotOfflineTest {
    @Test void jdbcTimestampCanBeSavedAndComparedWithoutLosingPrecision() throws Exception {
        var field=Bootstrap.class.getDeclaredField("JSON");
        field.setAccessible(true);
        var json=(ObjectMapper)field.get(null);
        var time=LocalDateTime.parse("2026-09-13T03:17:47.123456");
        var row=Map.of("id",1L,"created_at",time);
        var snapshot=Map.of("app_user",List.of(row));
        var saved=json.readTree(json.writeValueAsBytes(snapshot));
        assertEquals(1L,saved.path("app_user").get(0).path("id").asLong());
        assertEquals(time,json.treeToValue(saved.path("app_user").get(0).path("created_at"),LocalDateTime.class));
        assertNotEquals(json.valueToTree(time),json.valueToTree(time.plusNanos(1000)));
    }
}
