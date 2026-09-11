package com.ticketing.booking.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.springframework.core.env.Environment;

public final class Bootstrap {
    public static final Path ROOT = Path.of("D:/Codex/ticketing");
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Ready(HikariDataSource dataSource, RuntimeServices runtime, String runId) implements AutoCloseable {
        @Override public void close() { dataSource.close(); }
    }

    private Bootstrap() { }

    public static Ready prepare(Environment env) {
        require(Arrays.equals(env.getActiveProfiles(), new String[]{"biz01-it"}), "profile");
        for (var item : Map.of("server.address","127.0.0.1","server.port","18083",
                "biz.db.name","ticketing_biz01_it","biz.db.user","ticketing_biz01_it_app",
                "biz.db.migrator","ticketing_biz01_it_migrator","biz.db.host","127.0.0.1",
                "biz.db.port","13306","biz.redis.host","127.0.0.1","biz.redis.port","16379").entrySet()) {
            require(item.getValue().equals(env.getProperty(item.getKey())), item.getKey());
        }
        String runId = env.getProperty("biz.runId", "");
        require(runId.matches("[a-z0-9-]{8,50}"), "runId");
        try {
            Class<?> fixture = Class.forName("com.ticketing.booking.fixture.TestRuntime");
            Path origin = Path.of(fixture.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            require(origin.equals(ROOT.resolve("target/test-classes").toRealPath()), "test classpath");
            RuntimeServices runtime = (RuntimeServices) fixture.getMethod("instance").invoke(null);
            require(runtime != null, "identity fixture");
            var credentials = JSON.readTree(Files.readString(ROOT.resolve(".local/credentials.json")));
            for (String key : new String[]{"itApp","itMigrator","tokenA","tokenB","trustPassword"}) {
                require(credentials.path(key).asText().matches("[a-f0-9]{64}"), "private credential shape");
            }
            // Fixture proves both private identities can be resolved before any database connection.
            require(runtime.authenticate("Bearer " + credentials.path("tokenA").asText()) > 0, "identity A");
            require(runtime.authenticate("Bearer " + credentials.path("tokenB").asText()) > 0, "identity B");
            try (java.net.ServerSocket reservation = new java.net.ServerSocket()) {
                reservation.setReuseAddress(false);
                reservation.bind(new java.net.InetSocketAddress("127.0.0.1",18083));
            }
            int applied;
            Path evidence = ROOT.resolve("evidence/BIZ-01-I-001").resolve(runId);
            Files.createDirectories(evidence);
            try (HikariDataSource migration = pool("ticketing_biz01_it_migrator",
                    credentials.path("itMigrator").asText(), credentials.path("trustPassword").asText(), 1)) {
                Flyway flyway = Flyway.configure().dataSource(migration)
                    .locations("classpath:db/migration/biz01").cleanDisabled(true)
                    .baselineOnMigrate(false).validateMigrationNaming(true).validateOnMigrate(true).load();
                var before = flyway.validateWithResult();
                JSON.writeValue(evidence.resolve("pre-migration-validation-" + System.nanoTime() + ".json").toFile(),before);
                // migrate validates applied history while permitting the pending V1 on an empty schema.
                applied = flyway.migrate().migrationsExecuted;
                flyway.validate();
            }
            Path result = evidence.resolve("migration-" + System.nanoTime() + ".json");
            JSON.writeValue(result.toFile(), Map.of("at", Instant.now().toString(), "database","ticketing_biz01_it",
                "migrationsExecuted",applied,"tls","VERIFY_CA","profile","biz01-it","httpOpened",false));
            return new Ready(pool("ticketing_biz01_it_app", credentials.path("itApp").asText(),
                    credentials.path("trustPassword").asText(), 6), runtime, runId);
        } catch (Exception failure) {
            // Never put credential-bearing configuration or JDBC driver messages into an API/log.
            throw new IllegalStateException("BIZ01_STARTUP_REFUSED:" + failure.getClass().getSimpleName());
        }
    }

    public static HikariDataSource pool(String user, String password, String trustPassword, int size) {
        require(user.equals("ticketing_biz01_it_app") || user.equals("ticketing_biz01_it_migrator"), "account");
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:mysql://127.0.0.1:13306/ticketing_biz01_it?sslMode=VERIFY_CA"
            + "&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&connectTimeout=3000&socketTimeout=5000");
        cfg.setUsername(user);
        cfg.setPassword(password);
        cfg.addDataSourceProperty("trustCertificateKeyStoreUrl", ROOT.resolve(".local/mysql-trust.p12").toUri().toString());
        cfg.addDataSourceProperty("trustCertificateKeyStoreType", "PKCS12");
        cfg.addDataSourceProperty("trustCertificateKeyStorePassword", trustPassword);
        cfg.addDataSourceProperty("fallbackToSystemTrustStore", "false");
        cfg.setMaximumPoolSize(size);
        cfg.setMinimumIdle(0);
        cfg.setConnectionTimeout(3000);
        cfg.setValidationTimeout(2000);
        cfg.setConnectionInitSql("SET time_zone = '+00:00', innodb_lock_wait_timeout = 2");
        cfg.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        cfg.setPoolName(user);
        return new HikariDataSource(cfg);
    }

    private static void require(boolean valid, String guard) {
        if (!valid) { throw new IllegalStateException("BIZ01_STARTUP_REFUSED:" + guard); }
    }
}
