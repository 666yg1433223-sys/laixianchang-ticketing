package com.ticketing.booking.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Arrays;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.springframework.core.env.Environment;

/** Opt-in, loopback-only learning deployment with its own database and Redis instance. */
public final class LearningBootstrap {
    private LearningBootstrap() { }

    static boolean enabled(Environment environment) {
        return "true".equals(environment.getProperty("biz.learning.enabled"));
    }

    public static Bootstrap.Ready prepare(Environment environment) {
        validateEnvironment(environment);
        // Read all credentials before connecting or migrating so a partial setup fails early.
        String appPassword = requiredSecret(environment, "TICKETING_DB_PASSWORD");
        String migratorPassword = requiredSecret(environment, "TICKETING_MIGRATOR_PASSWORD");
        requiredSecret(environment, "TICKETING_REDIS_PASSWORD");
        try {
            try (var reservation = new java.net.ServerSocket()) {
                reservation.setReuseAddress(false);
                reservation.bind(new java.net.InetSocketAddress("127.0.0.1", 18082));
            }
            try (var migration = new HikariDataSource(poolConfiguration("ticketing_learning_migrator", migratorPassword, 1))) {
                var flyway = Flyway.configure().dataSource(migration)
                    .locations("classpath:db/migration/biz01", "classpath:db/migration/product")
                    .cleanDisabled(true).baselineOnMigrate(false).validateMigrationNaming(true).validateOnMigrate(true).load();
                flyway.migrate();
                flyway.validate();
            }
            return new Bootstrap.Ready(new HikariDataSource(poolConfiguration("ticketing_learning_app", appPassword, 8)),
                new LocalRuntime(true), "learning");
        } catch (Exception failure) {
            // Connection errors must not expose environment credentials.
            throw new IllegalStateException("LEARNING_STARTUP_REFUSED:" + failure.getClass().getSimpleName());
        }
    }

    static void validateEnvironment(Environment environment) {
        require(enabled(environment), "explicit learning mode");
        require(Arrays.equals(environment.getActiveProfiles(), new String[]{"local-sim"}), "profile");
        for (var entry : Map.of("server.address", "127.0.0.1", "server.port", "18082",
                "biz.db.name", "ticketing_learning", "biz.db.user", "ticketing_learning_app",
                "biz.db.migrator", "ticketing_learning_migrator", "biz.db.host", "127.0.0.1",
                "biz.db.port", "13316", "biz.redis.host", "127.0.0.1", "biz.redis.port", "16389").entrySet()) {
            require(entry.getValue().equals(environment.getProperty(entry.getKey())), entry.getKey());
        }
        require("true".equals(environment.getProperty("biz.simulation.enabled")), "simulated payments");
        require("false".equals(environment.getProperty("biz.sim.management.enabled", "false")), "private test controls disabled");
        require(environment.getProperty("biz.local.run-id", "").isEmpty(), "private lifecycle controls disabled");
        String admin = environment.getProperty("biz.local.admin-phone", "");
        require(admin.isEmpty() || admin.matches("1[3-9][0-9]{9}"), "local administrator");
    }

    static String requiredSecret(Environment environment, String key) {
        String value = environment.getProperty(key, "");
        require(value.matches("[a-f0-9]{64}"), key);
        return value;
    }

    static HikariConfig poolConfiguration(String user, String password, int size) {
        require(user.equals("ticketing_learning_app") || user.equals("ticketing_learning_migrator"), "account");
        require(password.matches("[a-f0-9]{64}"), "database credential");
        var config = new HikariConfig();
        // The learning containers publish only loopback ports; this path never targets the original TLS database.
        config.setJdbcUrl("jdbc:mysql://127.0.0.1:13316/ticketing_learning?sslMode=DISABLED&allowPublicKeyRetrieval=true"
            + "&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&connectTimeout=3000&socketTimeout=5000");
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(size);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(3000);
        config.setValidationTimeout(2000);
        config.setConnectionInitSql("SET time_zone = '+00:00', innodb_lock_wait_timeout = 2");
        config.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        config.setPoolName(user);
        return config;
    }

    private static void require(boolean valid, String guard) {
        if (!valid) { throw new IllegalStateException("LEARNING_STARTUP_REFUSED:" + guard); }
    }
}
