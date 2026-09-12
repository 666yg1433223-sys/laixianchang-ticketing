package com.ticketing.booking.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** One-time recovery for this turn's empty product V6 attempt; never run on nonempty data. */
class ProductMigrationRecoveryTest {
    @Test void recoverThisEmptyFailedMigration() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("product.recover.empty-v6"));
        var json=new ObjectMapper().findAndRegisterModules();
        var secret=json.readTree(Files.readString(Bootstrap.ROOT.resolve(".local/credentials.json")));
        try(var pool=LocalBootstrap.pool("ticketing_biz01_migrator",secret.path("bizMigrator").asText(),secret.path("trustPassword").asText(),1)) {
            var jdbc=new org.springframework.jdbc.core.JdbcTemplate(pool);
            assertEquals("ticketing_biz01",jdbc.queryForObject("SELECT DATABASE()",String.class));
            var tables=jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()",String.class);
            assertTrue(tables.contains("waitlist_entry"));assertFalse(tables.contains("processing_retry"));
            for(String table:tables) { assertTrue(table.matches("[a-z_]+"));if(!table.equals("flyway_schema_history")) { assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class),table); } }
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='booking_order' AND column_name='waitlist_entry_id'",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='6' AND success=0",Integer.class));
            assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1",Integer.class));
            var flyway=Flyway.configure().dataSource(pool).locations("classpath:db/migration/biz01","classpath:db/migration/product").cleanDisabled(true).load();
            var validated=flyway.validateWithResult();
            assertEquals(1,validated.invalidMigrations.size());assertEquals("6",validated.invalidMigrations.get(0).version);
            var evidence=Bootstrap.ROOT.resolve("evidence/product/direct-0913/v6-recovery");Files.createDirectories(evidence);
            var facts=new LinkedHashMap<String,Object>();facts.put("history",jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank"));
            for(String table:tables) { facts.put(table,jdbc.queryForMap("SHOW CREATE TABLE "+table)); }
            json.writeValue(evidence.resolve("before.json").toFile(),facts);
            // Preserve our proven-empty first statement; V6 resumes via CREATE IF NOT EXISTS.
            flyway.repair();flyway.migrate();flyway.validate();
            assertEquals(6,jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1",Integer.class));
            json.writeValue(evidence.resolve("result.json").toFile(),Map.of("database","ticketing_biz01","oldBusinessRows",0,"preservedEmptyOwnTable","waitlist_entry","versionsValidated",6));
        }
    }
}
