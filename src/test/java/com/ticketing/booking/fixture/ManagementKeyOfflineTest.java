package com.ticketing.booking.fixture;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ManagementKeyOfflineTest {
    @Test void sameLogicalRetryKeepsKeyButSeparateRunsDoNotShareIt() {
        String key=BookingHarness.managementKey("first-run","same-valid-key");
        assertTrue(key.matches("[a-f0-9]{64}"));
        assertEquals(key,BookingHarness.managementKey("first-run","same-valid-key"));
        assertNotEquals(key,BookingHarness.managementKey("second-run","same-valid-key"));
        assertNotEquals(key,BookingHarness.managementKey("first-run","different-key"));
    }
    @Test void invalidKeysReachServerUnchangedForNegativeTests() {
        assertNull(BookingHarness.managementKey("first-run",null));
        for(String key:new String[]{"","short","has spaces 123","x".repeat(65)})
            assertEquals(key,BookingHarness.managementKey("first-run",key));
    }
}
