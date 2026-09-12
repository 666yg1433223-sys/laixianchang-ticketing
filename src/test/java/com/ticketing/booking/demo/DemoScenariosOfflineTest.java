package com.ticketing.booking.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.booking.fixture.BookingHarness.Reply;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Parser/guard checks only; these are not replacements for real HTTP scenario runs. */
class DemoScenariosOfflineTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void acceptsPositiveStringIdsFromActualEnvelopes() throws Exception {
        assertEquals(42L, DemoScenarios.requiredId(json.readTree("{\"requestId\":\"42\"}").path("requestId")));
        assertEquals(Long.MAX_VALUE, DemoScenarios.requiredId(json.readTree("\"9223372036854775807\"")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "0", "42", "\"0\"", "\"-1\"", "\"01\"", "\" 42\"", "\"1e2\"", "\"9223372036854775808\"", "{}", "[]"})
    void rejectsMissingMalformedAndOverflowIds(String value) throws Exception {
        var node = json.readTree(value);
        assertThrows(IllegalStateException.class, () -> DemoScenarios.requiredId(node));
    }

    @Test void missingIdCannotBecomeZeroOrFixtureFallback() throws Exception {
        var node = json.readTree("{}").path("attemptId");
        assertThrows(IllegalStateException.class, () -> DemoScenarios.requiredId(node));
    }

    @Test void aSuccessfulHttpCodeDoesNotProveBusinessCompletion() throws Exception {
        Reply accepted = new Reply(202, json.readTree("{\"settlementStatus\":\"UNRESOLVED\"}"), Map.of());
        assertDoesNotThrow(() -> DemoScenarios.requireStatus(accepted, 202));
        assertThrows(IllegalStateException.class, () -> DemoScenarios.requireText(accepted.body(), "settlementStatus", "APPLIED"));
        assertThrows(IllegalStateException.class, () -> DemoScenarios.requireStatus(accepted, 200));
    }

    @Test void failurePayloadsAndMissingStateDoNotMasqueradeAsSuccess() throws Exception {
        var failure = json.readTree("{\"code\":\"DEPENDENCY_UNAVAILABLE\"}");
        assertThrows(IllegalStateException.class, () -> DemoScenarios.requireText(failure, "orderStatus", "PAID"));
        assertDoesNotThrow(() -> DemoScenarios.requireText(failure, "code", "DEPENDENCY_UNAVAILABLE"));
    }
}
