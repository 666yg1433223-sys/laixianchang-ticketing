package com.ticketing.booking.catalogue;

import com.ticketing.booking.config.RedisConfiguration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class CatalogueCacheConfigurationOfflineTest {
    private static final String LEARNING_PASSWORD = "a".repeat(64);

    private static MockEnvironment learningEnvironment() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("local-sim");
        Map.of("server.address", "127.0.0.1", "server.port", "18082", "biz.db.name", "ticketing_learning",
            "biz.db.user", "ticketing_learning_app", "biz.db.migrator", "ticketing_learning_migrator",
            "biz.db.host", "127.0.0.1", "biz.db.port", "13316", "biz.redis.host", "127.0.0.1", "biz.redis.port", "16389")
            .forEach(environment::setProperty);
        return environment.withProperty("biz.learning.enabled", "true")
            .withProperty("biz.simulation.enabled", "true")
            .withProperty("TICKETING_REDIS_PASSWORD", LEARNING_PASSWORD);
    }

    private static RedisStandaloneConfiguration sharedRedisSettings(MockEnvironment environment) {
        // Exercise the production environment resolver without starting either Redis client or reading old secrets.
        return ReflectionTestUtils.invokeMethod(RedisConfiguration.class, "redisSettings", environment);
    }

    @Test void learningRedissonUsesTheSameIsolatedAddressAndEnvironmentSecretAsLettuce() {
        var shared = sharedRedisSettings(learningEnvironment());
        var redisson = CatalogueCacheConfiguration.redissonSettings(shared).useSingleServer();
        assertEquals("redis://127.0.0.1:16389", redisson.getAddress());
        assertEquals(LEARNING_PASSWORD, redisson.getPassword());
        assertEquals(shared.getDatabase(), redisson.getDatabase());
        assertEquals(new String(shared.getPassword().get()), redisson.getPassword());
    }

    @Test void legacyLocalConfigurationKeepsItsAddressAndPreviouslyResolvedCredential() {
        // Supply a fake legacy credential: this test never invokes the legacy filesystem resolver.
        var shared = new RedisStandaloneConfiguration("127.0.0.1", 16379);
        shared.setPassword("test-only-legacy-password");
        var redisson = CatalogueCacheConfiguration.redissonSettings(shared).useSingleServer();
        assertEquals("redis://127.0.0.1:16379", redisson.getAddress());
        assertEquals("test-only-legacy-password", redisson.getPassword());
        assertEquals(0, redisson.getDatabase());
    }

    @Test void learningSettingsCannotFallBackToLegacyRedisOrMissingCredentials() {
        var wrongPort = learningEnvironment().withProperty("biz.redis.port", "16379");
        assertThrows(IllegalStateException.class, () -> sharedRedisSettings(wrongPort));
        var missingSecret = learningEnvironment().withProperty("TICKETING_REDIS_PASSWORD", "");
        assertThrows(IllegalStateException.class, () -> sharedRedisSettings(missingSecret));
    }

    @Test void sharedConfigurationRetainsTheBoundedCacheClientResourcesAndTimeouts() {
        var configuration = CatalogueCacheConfiguration.redissonSettings(sharedRedisSettings(learningEnvironment()));
        var server = configuration.useSingleServer();
        assertEquals(2, configuration.getThreads());
        assertEquals(2, configuration.getNettyThreads());
        assertEquals(1, server.getConnectionMinimumIdleSize());
        assertEquals(4, server.getConnectionPoolSize());
        assertEquals(1, server.getSubscriptionConnectionMinimumIdleSize());
        assertEquals(2, server.getSubscriptionConnectionPoolSize());
        assertEquals(1500, server.getConnectTimeout());
        assertEquals(1500, server.getTimeout());
        assertEquals(0, server.getRetryAttempts());
    }
}
