package com.ticketing.booking.config;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class LearningBootstrapOfflineTest {
    private static final String PASSWORD = "a".repeat(64);

    private MockEnvironment environment() {
        var env = new MockEnvironment();
        env.setActiveProfiles("local-sim");
        Map.of("server.address", "127.0.0.1", "server.port", "18082", "biz.db.name", "ticketing_learning",
            "biz.db.user", "ticketing_learning_app", "biz.db.migrator", "ticketing_learning_migrator",
            "biz.db.host", "127.0.0.1", "biz.db.port", "13316", "biz.redis.host", "127.0.0.1", "biz.redis.port", "16389")
            .forEach(env::setProperty);
        return env.withProperty("biz.learning.enabled", "true").withProperty("biz.simulation.enabled", "true")
            .withProperty("TICKETING_DB_PASSWORD", PASSWORD).withProperty("TICKETING_MIGRATOR_PASSWORD", PASSWORD)
            .withProperty("TICKETING_REDIS_PASSWORD", PASSWORD);
    }

    @Test void learningModeCannotTargetTheOriginalInfrastructureOrPublicInterfaces() {
        assertDoesNotThrow(() -> LearningBootstrap.validateEnvironment(environment()));
        for (var entry : Map.of("server.address", "0.0.0.0", "server.port", "8080",
                "biz.db.name", "ticketing_biz01", "biz.db.user", "root", "biz.db.migrator", "ticketing_biz01_migrator",
                "biz.db.host", "192.0.2.1", "biz.db.port", "13306", "biz.redis.host", "192.0.2.1", "biz.redis.port", "16379").entrySet()) {
            var env = environment().withProperty(entry.getKey(), entry.getValue());
            assertThrows(IllegalStateException.class, () -> Bootstrap.prepare(env));
        }
    }

    @Test void learningModeRequiresAnExplicitLocalProfileAndDisablesPrivateControls() {
        for (String[] profiles : new String[][]{{}, {"biz01-it"}, {"local-sim", "biz01-it"}}) {
            var env = environment();
            env.setActiveProfiles(profiles);
            assertThrows(IllegalStateException.class, () -> Bootstrap.prepare(env));
        }
        for (var entry : Map.of("biz.learning.enabled", "false", "biz.simulation.enabled", "false",
                "biz.sim.management.enabled", "true", "biz.local.run-id", "local-12345678").entrySet()) {
            var env = environment().withProperty(entry.getKey(), entry.getValue());
            assertThrows(IllegalStateException.class, () -> Bootstrap.prepare(env));
        }
        assertThrows(IllegalStateException.class, () -> LocalBootstrap.validateEnvironment(environment()));
    }

    @Test void missingOrPlaceholderCredentialsFailBeforeAnyNetworkOrMigration() {
        for (String key : new String[]{"TICKETING_DB_PASSWORD", "TICKETING_MIGRATOR_PASSWORD", "TICKETING_REDIS_PASSWORD"}) {
            for (String value : new String[]{"", "replace-me", "secret-should-not-appear"}) {
                var env = environment().withProperty(key, value);
                var failure = assertThrows(IllegalStateException.class, () -> Bootstrap.prepare(env));
                assertEquals("LEARNING_STARTUP_REFUSED:" + key, failure.getMessage());
            }
        }
    }

    @Test void databaseAndRedisUseOnlyTheDedicatedLoopbackServicesWithoutPrivateFiles() throws Exception {
        var config = LearningBootstrap.poolConfiguration("ticketing_learning_app", PASSWORD, 8);
        assertTrue(config.getJdbcUrl().startsWith("jdbc:mysql://127.0.0.1:13316/ticketing_learning?"));
        assertTrue(config.getJdbcUrl().contains("sslMode=DISABLED"));
        assertEquals("ticketing_learning_app", config.getUsername());
        assertEquals("TRANSACTION_READ_COMMITTED", config.getTransactionIsolation());
        assertFalse(config.getDataSourceProperties().containsKey("trustCertificateKeyStoreUrl"));
        var redis = RedisConfiguration.redisSettings(environment());
        assertEquals("127.0.0.1", redis.getHostName());
        assertEquals(16389, redis.getPort());
        assertEquals(PASSWORD, new String(redis.getPassword().get()));
        assertEquals(0, redis.getDatabase());
        for (String user : new String[]{"root", "ticketing_biz01_app", "ticketing_biz01_it_migrator"}) {
            assertThrows(IllegalStateException.class, () -> LearningBootstrap.poolConfiguration(user, PASSWORD, 1));
        }
    }
}
