package com.ticketing.booking.integration;

import com.ticketing.booking.fixture.BookingHarness;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrderCloseSmokeTest {
    @Test void firstHttpCancelReturnsInventory() throws Exception {
        try (var h = new BookingHarness(System.getProperty("biz.runId") + "-close")) {
            var f = h.fixture("S01", 1);
            var accepted = h.post(f, "s01-first-cancel");
            assertEquals(202, accepted.status());
            var hold = h.query(accepted.id()).body().path("order");
            String order = hold.path("orderId").asText();
            assertEquals("HOLD", hold.path("orderStatus").asText());
            var cancelled = h.request("POST", "/v1/orders/" + order + "/cancel", "{}", null, false, true);
            assertEquals(200, cancelled.status());
            assertEquals("CANCELLED", cancelled.body().path("orderStatus").asText());
            assertEquals("USER_CANCELLED", cancelled.body().path("closeReason").asText());
            assertEquals(cancelled.body(), h.request("GET", "/v1/orders/" + order, null, null, false, true).body());
            var original = h.query(accepted.id()).body();
            assertEquals("SUCCEEDED", original.path("status").asText());
            for (var fields = original.path("order").fields(); fields.hasNext();) {
                var field = fields.next();
                assertEquals(field.getValue(), cancelled.body().path(field.getKey()));
            }
            h.assertCounts(f, 1, 1, 2, 1);
            h.pass(f, "Real HTTP cancellation; one RELEASE, returned stock, NONE quota, historical SUCCEEDED retained.");
            h.cleanOnClose = true;
        }
    }
}
