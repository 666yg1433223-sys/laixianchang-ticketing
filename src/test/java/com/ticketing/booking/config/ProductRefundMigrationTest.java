package com.ticketing.booking.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Forward-migration check on existing local product data while the website is stopped. */
class ProductRefundMigrationTest {
    record Snapshot(List<String> columns,List<String> key,long count,String sha256) { }
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();

    @Test void v7PreservesEveryExistingBusinessColumnAndRow() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("product.refund.migrate"));
        try(var reservation=new ServerSocket()) {
            reservation.setReuseAddress(false);
            reservation.bind(new InetSocketAddress("127.0.0.1",18082));
        }
        var secrets=json.readTree(Files.readString(Bootstrap.ROOT.resolve(".local/credentials.json")));
        try(var pool=LocalBootstrap.pool("ticketing_biz01_migrator",secrets.path("bizMigrator").asText(),secrets.path("trustPassword").asText(),1)) {
            var jdbc=new JdbcTemplate(pool);
            assertEquals("ticketing_biz01",jdbc.queryForObject("SELECT DATABASE()",String.class));
            assertEquals(6,jdbc.queryForObject("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1",Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=0",Integer.class));
            var directory=Bootstrap.ROOT.resolve("evidence/product/refund-0913/migration-"+UUID.randomUUID());
            Files.createDirectories(directory);
            var before=snapshots(jdbc,null);
            json.writeValue(directory.resolve("before.json").toFile(),before);
            var flyway=Flyway.configure().dataSource(pool).locations("classpath:db/migration/biz01","classpath:db/migration/product")
                .cleanDisabled(true).baselineOnMigrate(false).validateMigrationNaming(true).validateOnMigrate(true).load();
            flyway.migrate();
            flyway.validate();
            var after=snapshots(jdbc,before);
            json.writeValue(directory.resolve("after.json").toFile(),after);
            assertEquals(before,after,"Existing business rows and columns must survive V7 unchanged");
            assertEquals(7,jdbc.queryForObject("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1",Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM booking_order WHERE current_refund_id IS NOT NULL OR refunded_at IS NOT NULL",Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM refund_request",Integer.class));
            json.writeValue(directory.resolve("result.json").toFile(),Map.of("oldVersion",6,"newVersion",7,"tables",before.size(),"oldRows",before.values().stream().mapToLong(Snapshot::count).sum(),"oldColumnsAndRowsUnchanged",true));
            System.out.println("REFUND_MIGRATION_VERIFIED:"+directory);
        }
    }

    private Map<String,Snapshot> snapshots(JdbcTemplate jdbc,Map<String,Snapshot> shapes) throws Exception {
        var result=new LinkedHashMap<String,Snapshot>();
        var tables=shapes==null?jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name<>'flyway_schema_history' ORDER BY table_name",String.class):new ArrayList<>(shapes.keySet());
        for(String table:tables) {
            var columns=shapes==null?jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? ORDER BY ordinal_position",String.class,table):shapes.get(table).columns();
            var key=shapes==null?jdbc.queryForList("SELECT column_name FROM information_schema.key_column_usage WHERE table_schema=DATABASE() AND table_name=? AND constraint_name='PRIMARY' ORDER BY ordinal_position",String.class,table):shapes.get(table).key();
            assertFalse(key.isEmpty(),table);
            var rows=jdbc.queryForList("SELECT "+columns.stream().map(this::identifier).collect(java.util.stream.Collectors.joining(","))+" FROM "+identifier(table)+" ORDER BY "+key.stream().map(this::identifier).collect(java.util.stream.Collectors.joining(",")));
            String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(rows)));
            result.put(table,new Snapshot(columns,key,rows.size(),digest));
        }
        return result;
    }

    private String identifier(String name) {
        if(!name.matches("[a-z0-9_]+")) { throw new IllegalArgumentException("UNEXPECTED_SCHEMA_IDENTIFIER"); }
        return "`"+name+"`";
    }
}
