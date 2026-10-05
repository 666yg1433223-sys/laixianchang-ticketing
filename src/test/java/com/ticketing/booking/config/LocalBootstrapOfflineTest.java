package com.ticketing.booking.config;

import com.ticketing.booking.domain.ApiFailure;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalBootstrapOfflineTest {
    private MockEnvironment environment() {
        var env = new MockEnvironment();
        env.setActiveProfiles("local-sim");
        Map.of("server.address", "127.0.0.1", "server.port", "18082", "biz.db.name", "ticketing_biz01",
            "biz.db.user", "ticketing_biz01_app", "biz.db.migrator", "ticketing_biz01_migrator",
            "biz.db.host", "127.0.0.1", "biz.db.port", "13306", "biz.redis.host", "127.0.0.1", "biz.redis.port", "16379")
            .forEach(env::setProperty);
        return env;
    }

    @Test void localProductCannotPointAtTheOldProjectOrTheIntegrationSchema() {
        var env = environment();
        assertDoesNotThrow(() -> LocalBootstrap.validateEnvironment(env));
        for (var entry : Map.of("server.address", "0.0.0.0", "server.port", "18083", "biz.db.name", "ticketing_biz01_it",
                "biz.db.user", "root", "biz.db.port", "3306", "biz.redis.port", "6379").entrySet()) {
            var invalid = environment().withProperty(entry.getKey(), entry.getValue());
            assertThrows(IllegalStateException.class, () -> LocalBootstrap.validateEnvironment(invalid));
        }
    }

    @Test void combinedOrImplicitProfilesRemainRefused() {
        for (String[] profiles : new String[][]{{}, {"biz01"}, {"local-sim", "biz01-it"}, {"biz01-it"}}) {
            var env = environment(); env.setActiveProfiles(profiles);
            assertThrows(IllegalStateException.class, () -> LocalBootstrap.validateEnvironment(env));
        }
    }

    @Test void localAccountPoolRejectsOtherAccountsBeforeConnecting() {
        for (String user : new String[]{"root", "ticketing_biz01_it_app", "hmdp", "ticketing_biz01_it_migrator"}) {
            assertThrows(IllegalStateException.class, () -> LocalBootstrap.pool(user, "not-a-password", "unused", 1));
        }
    }

    @Test void runtimeHasNoTestHooksAndKeepsBookingSeparateFromSettlementScans() {
        var runtime = new LocalRuntime(true);
        assertTrue(runtime.productEnabled());
        assertFalse(runtime.directEnabled());
        assertFalse(runtime.recoveryEnabled());
        assertTrue(runtime.settlementRecoveryEnabled());
        assertFalse(runtime.managementReady());
        assertTrue(runtime.managementAttempts().isEmpty());
        assertDoesNotThrow(() -> runtime.at("anything", 42));
        assertEquals(new RuntimeServices.SimulationPlan("SUCCESS", 0), runtime.simulationPlan());
        assertFalse(new LocalRuntime(false).simulatorEnabled());
        assertThrows(IllegalStateException.class, () -> new LocalRuntime(false).simulationPlan());
    }

    @Test void identityBindingIsRequiredAndCannotBeReplaced() {
        var runtime = new LocalRuntime(true);
        assertEquals(503, assertThrows(ApiFailure.class, () -> runtime.authenticate("anything")).status());
        runtime.bindIdentity(authorization -> 73);
        assertEquals(73, runtime.authenticate("server checked value"));
        assertThrows(IllegalStateException.class, () -> runtime.bindIdentity(authorization -> 99));
    }

    @Test void businessTimeComesFromTheDatabaseNotAnAdjustableRequestHook() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)", LocalDateTime.class))
            .thenReturn(LocalDateTime.parse("2026-09-13T04:00:00.123"));
        assertEquals(Instant.parse("2026-09-13T04:00:00.123Z"), new LocalRuntime(true).afterLocks(new com.ticketing.booking.repository.DatabaseTimeRepository(jdbc)));
    }
}
