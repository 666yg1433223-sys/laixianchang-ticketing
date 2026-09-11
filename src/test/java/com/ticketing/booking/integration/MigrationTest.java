package com.ticketing.booking.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.booking.config.Bootstrap;
import java.nio.file.Files;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

class MigrationTest {
    static MockEnvironment environment() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("biz01-it");
        Map.of("server.address","127.0.0.1","server.port","18083","biz.db.name","ticketing_biz01_it",
            "biz.db.user","ticketing_biz01_it_app","biz.db.migrator","ticketing_biz01_it_migrator",
            "biz.db.host","127.0.0.1","biz.db.port","13306","biz.redis.host","127.0.0.1","biz.redis.port","16379")
            .forEach(env::setProperty);
        env.setProperty("biz.runId",System.getProperty("biz.runId","biz01-migration-001"));
        return env;
    }
    @Test void migrationAndLeastPrivilege() throws Exception {
        var json = new ObjectMapper().findAndRegisterModules();
        var evidence = Bootstrap.ROOT.resolve("evidence/BIZ-03-I-001").resolve(environment().getProperty("biz.runId"));
        Files.createDirectories(evidence);
        var secrets = json.readTree(Files.readString(Bootstrap.ROOT.resolve(".local/credentials.json")));
        java.util.Map<String,Object> before = new java.util.LinkedHashMap<>();
        try (var probe = Bootstrap.pool("ticketing_biz01_it_app", secrets.path("itApp").asText(), secrets.path("trustPassword").asText(), 1)) {
            var jdbc = new JdbcTemplate(probe);
            for (String table : java.util.List.of("app_user", "event", "event_session", "ticket_tier")) {
                before.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
            }
            before.put("historyV1",jdbc.queryForList("SELECT * FROM flyway_schema_history WHERE version='1'"));
            assertEquals(243374070,jdbc.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version='2'",Integer.class));
            assertEquals(-576261309,jdbc.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version='1'",Integer.class));
            json.writeValue(evidence.resolve("M01-before-migration.json").toFile(),before);
        }
        try (var first = Bootstrap.prepare(environment())) {
            JdbcTemplate jdbc = new JdbcTemplate(first.dataSource());
            assertEquals("ticketing_biz01_it",jdbc.queryForObject("SELECT DATABASE()",String.class));
            assertEquals(11,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version='1'",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version='2'",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version='3'",Integer.class));
            assertEquals(-576261309,jdbc.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version='1'",Integer.class));
            for (String table : java.util.List.of("app_user", "event", "event_session", "ticket_tier")) {
                assertEquals(before.get(table),jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
            }
            assertEquals(before.get("historyV1"),jdbc.queryForList("SELECT * FROM flyway_schema_history WHERE version='1'"));
            json.writeValue(evidence.resolve("M01-after-migration.json").toFile(),Map.of("oldDataUnchanged",true,
                "history",jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank"),
                "businessDaoUser",jdbc.queryForObject("SELECT CURRENT_USER()",String.class)));
            assertThrows(Exception.class,()->jdbc.execute("CREATE TABLE forbidden_app_ddl(id INT)"));
            var tls = jdbc.queryForMap("SHOW STATUS LIKE 'Ssl_cipher'");
            assertFalse(tls.get("Value").toString().isBlank());
            var grants = jdbc.queryForList("SHOW GRANTS FOR CURRENT_USER()");
            json.writeValue(evidence.resolve("app-grants.json").toFile(),grants);
        }
        try (var repeat = Bootstrap.prepare(environment())) {
            assertEquals(1,new JdbcTemplate(repeat.dataSource()).queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='1'",Integer.class));
        }
    }
    @Test void guardsRejectBeforeDatabase() {
        var env = environment(); env.setActiveProfiles("biz01");
        assertThrows(IllegalStateException.class,()->Bootstrap.prepare(env));
        env.setActiveProfiles();
        assertThrows(IllegalStateException.class,()->Bootstrap.prepare(env));
        for (String key : new String[]{"server.port","biz.db.name","biz.db.user","biz.db.migrator","biz.db.host","biz.redis.port"}) {
            var invalid = environment().withProperty(key,"wrong");
            assertThrows(IllegalStateException.class,()->Bootstrap.prepare(invalid));
        }
    }
}
