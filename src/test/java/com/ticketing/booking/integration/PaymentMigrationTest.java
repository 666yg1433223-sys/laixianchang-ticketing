package com.ticketing.booking.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.booking.config.Bootstrap;
import com.ticketing.booking.persistence.BookingStore;
import java.nio.file.Files;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

class PaymentMigrationTest {
    @Test void forwardMigrationAndTypedBackfill() throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();
        var env=MigrationTest.environment();
        env.setProperty("biz.runId",env.getProperty("biz.runId")+"-v3");
        var dir=Bootstrap.ROOT.resolve("evidence/BIZ-03-I-001").resolve(env.getProperty("biz.runId"));
        Files.createDirectories(dir);
        var secrets=json.readTree(Files.readString(Bootstrap.ROOT.resolve(".local/credentials.json")));
        try(var probe=Bootstrap.pool("ticketing_biz01_it_app",secrets.path("itApp").asText(),secrets.path("trustPassword").asText(),1)) {
            var jdbc=new JdbcTemplate(probe); var store=new BookingStore(jdbc);
            var history=jdbc.queryForList("SELECT * FROM flyway_schema_history WHERE version IN ('1','2') ORDER BY installed_rank");
            assertEquals(2,history.size()); assertEquals(-576261309,history.get(0).get("checksum")); assertEquals(243374070,history.get(1).get("checksum"));
            boolean first=jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='3'",Integer.class)==0;
            Map<String,Object> old=new LinkedHashMap<>();
            for(String table:List.of("app_user","event","event_session","ticket_tier","booking_request","booking_order","user_session_quota","inventory_ledger","outbox_event"))
                old.put(table,jdbc.queryForList("SELECT * FROM "+table));
            json.writeValue(dir.resolve("V3-old-data-before.json").toFile(),old);
            long user=0,event=0,session=0,tier=0,request=0,outbox=0;
            if(first) {
                String prefix=env.getProperty("biz.runId")+":v3";
                user=store.insert("INSERT INTO app_user(subject_key) VALUES(?)",prefix);
                event=store.insert("INSERT INTO event(title) VALUES(?)",prefix);
                session=store.insert("INSERT INTO event_session(event_id,start_at,booking_open_at,booking_close_at) VALUES(?,'2030-06-01 12:00:00','2030-06-01 09:00:00','2030-06-01 11:00:00')",event);
                tier=store.insert("INSERT INTO ticket_tier(session_id,name,capacity,available,unit_price_cent) VALUES(?,'migration fixture',1,1,9900)",session);
                request=store.insert("INSERT INTO booking_request(user_id,session_id,tier_id,idempotency_key,payload_hash,accepted_at,process_deadline) VALUES(?,?,?,'migration-key',UNHEX(SHA2('fixture',256)),'2030-06-01 10:00:00','2030-06-01 10:02:00')",user,session,tier);
                outbox=store.insert("INSERT INTO outbox_event(event_type,aggregate_id,payload,next_attempt_at,created_at) VALUES('BOOKING_REQUESTED',?,JSON_OBJECT(),'2030-06-01 10:00:00','2030-06-01 10:00:00')",request);
                json.writeValue(dir.resolve("V3-exact-seed-manifest.json").toFile(),Map.of("user",user,"event",event,"session",session,"tier",tier,"request",request,"outbox",outbox,"prefix",prefix));
            }
            try(var ready=Bootstrap.prepare(env)) {
                assertEquals(11,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class));
                assertEquals(history,jdbc.queryForList("SELECT * FROM flyway_schema_history WHERE version IN ('1','2') ORDER BY installed_rank"));
                assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='BOOKING_REQUESTED' AND (booking_request_id IS NULL OR booking_request_id<>aggregate_id OR payment_attempt_id IS NOT NULL)",Integer.class));
                if(first) {
                    assertEquals(request,jdbc.queryForObject("SELECT booking_request_id FROM outbox_event WHERE id=?",Long.class,outbox));
                    json.writeValue(dir.resolve("V3-backfilled-row.json").toFile(),jdbc.queryForMap("SELECT * FROM outbox_event WHERE id=?",outbox));
                    jdbc.update("DELETE FROM outbox_event WHERE id=? AND aggregate_id=?",outbox,request);
                    jdbc.update("DELETE FROM booking_request WHERE id=? AND user_id=?",request,user);
                    jdbc.update("DELETE FROM ticket_tier WHERE id=? AND session_id=?",tier,session);
                    jdbc.update("DELETE FROM event_session WHERE id=? AND event_id=?",session,event);
                    jdbc.update("DELETE FROM event WHERE id=? AND title=?",event,env.getProperty("biz.runId")+":v3");
                    jdbc.update("DELETE FROM app_user WHERE id=? AND subject_key=?",user,env.getProperty("biz.runId")+":v3");
                }
                for(var entry:old.entrySet()) {
                    @SuppressWarnings("unchecked") var rows=(List<Map<String,Object>>)entry.getValue();
                    var after=jdbc.queryForList("SELECT * FROM "+entry.getKey());
                    assertEquals(rows.size(),after.size());
                    for(int i=0;i<rows.size();i++) for(var field:rows.get(i).entrySet()) assertEquals(json.valueToTree(field.getValue()),json.valueToTree(after.get(i).get(field.getKey())));
                }
                json.writeValue(dir.resolve("V3-migration-verified.json").toFile(),Map.of("firstMigration",first,"oldDataUnchanged",true,"backfillVerified",true,"seedCleaned",true,"history",jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank")));
            }
            try(var repeat=Bootstrap.prepare(env)) { assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='3' AND success=1",Integer.class)); }
            try(var paths=Files.list(dir)) {
                var records=paths.filter(p->p.getFileName().toString().startsWith("migration-")).sorted().toList();
                assertEquals(2,records.size());
                assertEquals(0,json.readTree(Files.readString(records.get(1))).path("migrationsExecuted").asInt(-1));
            }
        }
    }
}
