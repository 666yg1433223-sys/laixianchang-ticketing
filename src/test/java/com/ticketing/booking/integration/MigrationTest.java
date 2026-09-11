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
        try (var first = Bootstrap.prepare(environment())) {
            JdbcTemplate jdbc = new JdbcTemplate(first.dataSource());
            assertEquals("ticketing_biz01_it",jdbc.queryForObject("SELECT DATABASE()",String.class));
            assertEquals(10,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version='1'",Integer.class));
            assertThrows(Exception.class,()->jdbc.execute("CREATE TABLE forbidden_app_ddl(id INT)"));
            var tls = jdbc.queryForMap("SHOW STATUS LIKE 'Ssl_cipher'");
            assertFalse(tls.get("Value").toString().isBlank());
            var grants = jdbc.queryForList("SHOW GRANTS FOR CURRENT_USER()");
            new ObjectMapper().writeValue(Bootstrap.ROOT.resolve("evidence/BIZ-01-I-001/app-grants.json").toFile(),grants);
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
