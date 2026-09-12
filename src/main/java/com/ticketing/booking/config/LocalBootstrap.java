package com.ticketing.booking.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.springframework.core.env.Environment;

/** A packaged application can run locally without loading any fixture classes. */
public final class LocalBootstrap {
    private LocalBootstrap() { }

    public static Bootstrap.Ready prepare(Environment environment) {
        validateEnvironment(environment);
        try {
            var control = LocalRuntime.Control.open(environment.getProperty("biz.local.run-id", ""));
            var privateConfig = new ObjectMapper().readTree(Files.readString(Bootstrap.ROOT.resolve(".local/credentials.json")));
            for (String key : new String[]{"bizApp", "bizMigrator", "trustPassword"}) {
                require(privateConfig.path(key).asText().matches("[a-f0-9]{64}"), "private credential shape");
            }
            require(Files.isRegularFile(Bootstrap.ROOT.resolve(".local/mysql-trust.p12")), "database trust store");
            // Fail before migrations if another application already owns this product port.
            try (var reservation = new java.net.ServerSocket()) {
                reservation.setReuseAddress(false);
                reservation.bind(new java.net.InetSocketAddress("127.0.0.1", 18082));
            }
            try (var migration = pool("ticketing_biz01_migrator", privateConfig.path("bizMigrator").asText(),
                    privateConfig.path("trustPassword").asText(), 1)) {
                var flyway = Flyway.configure().dataSource(migration)
                    .locations("classpath:db/migration/biz01", "classpath:db/migration/product")
                    .cleanDisabled(true).baselineOnMigrate(false).validateMigrationNaming(true).validateOnMigrate(true).load();
                flyway.migrate();
                flyway.validate();
            }
            return new Bootstrap.Ready(pool("ticketing_biz01_app", privateConfig.path("bizApp").asText(),
                privateConfig.path("trustPassword").asText(), 8),
                new LocalRuntime("true".equals(environment.getProperty("biz.simulation.enabled")), control), "local-sim");
        } catch (Exception failure) {
            // Do not leak JDBC URLs containing secrets or the credential document in startup errors.
            throw new IllegalStateException("LOCAL_STARTUP_REFUSED:" + failure.getClass().getSimpleName());
        }
    }

    static void validateEnvironment(Environment environment) {
        require(Arrays.equals(environment.getActiveProfiles(), new String[]{"local-sim"}), "profile");
        for (var entry : Map.of("server.address", "127.0.0.1", "server.port", "18082",
                "biz.db.name", "ticketing_biz01", "biz.db.user", "ticketing_biz01_app",
                "biz.db.migrator", "ticketing_biz01_migrator", "biz.db.host", "127.0.0.1",
                "biz.db.port", "13306", "biz.redis.host", "127.0.0.1", "biz.redis.port", "16379").entrySet()) {
            require(entry.getValue().equals(environment.getProperty(entry.getKey())), entry.getKey());
        }
        String admin = environment.getProperty("biz.local.admin-phone", "");
        require(admin.isEmpty() || admin.matches("1[3-9][0-9]{9}"), "local administrator");
        String runId = environment.getProperty("biz.local.run-id", "");
        require(runId.isEmpty() || runId.matches("local-[a-z0-9-]{8,44}"), "local run");
    }

    static HikariDataSource pool(String user, String password, String trustPassword, int size) {
        require(user.equals("ticketing_biz01_app") || user.equals("ticketing_biz01_migrator"), "account");
        var config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://127.0.0.1:13306/ticketing_biz01?sslMode=VERIFY_CA"
            + "&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&connectTimeout=3000&socketTimeout=5000");
        config.setUsername(user);
        config.setPassword(password);
        config.addDataSourceProperty("trustCertificateKeyStoreUrl", Bootstrap.ROOT.resolve(".local/mysql-trust.p12").toUri().toString());
        config.addDataSourceProperty("trustCertificateKeyStoreType", "PKCS12");
        config.addDataSourceProperty("trustCertificateKeyStorePassword", trustPassword);
        config.addDataSourceProperty("fallbackToSystemTrustStore", "false");
        config.setMaximumPoolSize(size);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(3000);
        config.setValidationTimeout(2000);
        config.setConnectionInitSql("SET time_zone = '+00:00', innodb_lock_wait_timeout = 2");
        config.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        config.setPoolName(user);
        return new HikariDataSource(config);
    }

    private static void require(boolean valid, String guard) {
        if (!valid) { throw new IllegalStateException("LOCAL_STARTUP_REFUSED:" + guard); }
    }
}
