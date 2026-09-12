package com.ticketing.booking.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

class ProductMigrationDiagnosticTest {
    @Test void inspectProductSchemaOnly() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("product.inspect"));
        var secret=new ObjectMapper().readTree(Files.readString(Bootstrap.ROOT.resolve(".local/credentials.json")));
        try(var pool=LocalBootstrap.pool("ticketing_biz01_migrator",secret.path("bizMigrator").asText(),secret.path("trustPassword").asText(),1)) {
            var jdbc=new org.springframework.jdbc.core.JdbcTemplate(pool);
            System.out.println(jdbc.queryForList("SELECT version,description,success FROM flyway_schema_history ORDER BY installed_rank"));
            for(String table:new String[]{"waitlist_entry","booking_order","user_session_quota","outbox_event","processing_retry"}) {
                var present=jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",Integer.class,table);
                if(present>0) { System.out.println(jdbc.queryForMap("SHOW CREATE TABLE "+table)); }
            }
            if(Boolean.getBoolean("product.inspect.failed-ddl")) {
                org.junit.jupiter.api.Assertions.assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM booking_order",Integer.class));
                String script=Files.readString(Bootstrap.ROOT.resolve("src/main/resources/db/migration/product/V6__waitlist_and_stream.sql"));
                String statement=script.substring(script.indexOf("ALTER TABLE booking_order"),script.indexOf("-- Polymorphic")).trim();
                try { jdbc.execute(statement);System.out.println("ORDER_DDL_SUCCEEDED"); }
                catch(org.springframework.dao.DataAccessException failure) { System.out.println("ORDER_DDL_CAUSE:"+failure.getMostSpecificCause().getMessage()); }
            }
        }
    }
}
