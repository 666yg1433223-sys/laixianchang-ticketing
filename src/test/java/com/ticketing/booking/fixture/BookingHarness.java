package com.ticketing.booking.fixture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.booking.application.BookingApplication;
import com.ticketing.booking.config.Bootstrap;
import com.ticketing.booking.persistence.BookingStore;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import static com.ticketing.booking.domain.Values.*;
import static org.junit.jupiter.api.Assertions.*;

public final class BookingHarness implements AutoCloseable {
    public static final Instant BASE=Instant.parse("2030-06-01T10:00:00Z");
    public final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    public final String runId;
    public final Path evidence;
    public final TestRuntime runtime=TestRuntime.instance();
    public ConfigurableApplicationContext app;
    public final HikariDataSource probe;
    public final JdbcTemplate jdbc;
    public final BookingStore store;
    public final List<Fixture> fixtures=new ArrayList<>();
    public final Set<String> passed=new LinkedHashSet<>();
    public final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    public boolean cleanOnClose=false;
    private final boolean management;
    private final boolean demo;

    public record Fixture(String name,long userA,long userB,long event,long session,long tier,long otherTier) { }
    public record Reply(int status,JsonNode body,Map<String,List<String>> headers) {
        public long id() { return Long.parseLong(body.path("requestId").asText()); }
    }

    public BookingHarness(String runId) throws Exception {
        this(runId,false);
    }
    public BookingHarness(String runId,boolean management) throws Exception {
        this(runId,management,false);
    }
    public BookingHarness(String runId,boolean management,boolean demo) throws Exception {
        this.runId=runId;
        this.management=management;
        this.demo=demo;
        String task=System.getProperty("biz.evidenceTask",management?"BIZ-04-I-001":"BIZ-03-I-001");
        if(!Set.of("BIZ-03-I-001","BIZ-04-I-001").contains(task)) throw new IllegalArgumentException("Invalid evidence task");
        evidence=Bootstrap.ROOT.resolve("evidence").resolve(task).resolve(runId);
        Files.createDirectories(evidence);
        if(Files.exists(evidence.resolve("manifest.json"))) { throw new IllegalStateException("Used runId"); }
        runtime.disableManagement();runtime.reset(); runtime.fixedTime=BASE; runtime.observedRedisKeys.clear();
        if(demo) { runtime.enterDemo(); }
        var secret=json.readTree(Files.readString(Bootstrap.ROOT.resolve(".local/credentials.json")));
        probe=Bootstrap.pool("ticketing_biz01_it_app",secret.path("itApp").asText(),secret.path("trustPassword").asText(),2);
        jdbc=new JdbcTemplate(probe); store=new BookingStore(jdbc);
        try {
            if(management) {
                if(demo) runtime.configureDemoManagement(runId,jdbc,Bootstrap.ROOT.resolve(".local/demo").resolve(runId));
                else runtime.configureManagement(runId,jdbc);
            }
            startApp();
        } catch(Exception failure) { probe.close();throw failure; }
    }
    public void startApp() {
        SpringApplication application=new SpringApplication(BookingApplication.class);
        if(demo) application.addInitializers(context -> {
            var registration=new org.springframework.boot.web.servlet.FilterRegistrationBean<>(new com.ticketing.booking.demo.DemoIdentityFilter(runtime));
            registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE);
            registration.addUrlPatterns("/*");
            context.getBeanFactory().registerSingleton("demoIdentityGate",registration);
        });
        app=application.run("--spring.profiles.active=biz01-it","--biz.runId="+runId,"--biz.simulation.enabled=true","--biz.sim.management.enabled="+management);
    }
    public void stopApp() { if(app!=null){app.close(); app=null;} }
    public Fixture fixture(String name,int capacity) throws Exception {
        return fixtureAt(name,capacity,BASE);
    }
    public Fixture fixtureAt(String name,int capacity,Instant base) throws Exception {
        if(!name.matches("[A-Za-z0-9_-]{1,70}") || capacity<0) throw new IllegalArgumentException("Invalid fixture");
        runtime.reset(); runtime.fixedTime=demo?null:base;
        if(demo) { runtime.direct=false;runtime.recovery=false; }
        long a=store.insert("INSERT INTO app_user(subject_key,nickname) VALUES(?,?)",runId+":"+name+":a","fixture A");
        long b=store.insert("INSERT INTO app_user(subject_key,nickname) VALUES(?,?)",runId+":"+name+":b","fixture B");
        long event=store.insert("INSERT INTO event(title) VALUES(?)",runId+":"+name);
        long session=store.insert("INSERT INTO event_session(event_id,start_at,booking_open_at,booking_close_at,state) VALUES(?,?,?,?,'OPEN')",
            event,sqlTime(base.plusSeconds(7200)),sqlTime(base.minusSeconds(60)),sqlTime(base.plusSeconds(3600)));
        long tier=store.insert("INSERT INTO ticket_tier(session_id,name,capacity,available,unit_price_cent) VALUES(?,?,?,?,9900)",session,"standard",capacity,capacity);
        long other=store.insert("INSERT INTO ticket_tier(session_id,name,capacity,available,unit_price_cent) VALUES(?,?,?,?,11900)",session,"alternate",capacity,capacity);
        Fixture f=new Fixture(name,a,b,event,session,tier,other); fixtures.add(f);
        runtime.userA=a; runtime.userB=b;
        save("manifest.json",fixtures);
        runtime.enrollManagementFixture(jdbc,f);
        return f;
    }
    public void refreshManagementAttempts(Fixture f) throws Exception { runtime.enrollManagementFixture(jdbc,f); }
    public String input(Fixture f) { return "{\"sessionId\":\""+f.session()+"\",\"tierId\":\""+f.tier()+"\",\"quantity\":1}"; }
    public Reply post(Fixture f,String key) throws Exception { return request("POST","/v1/booking-requests",input(f),key,false,true); }
    public Reply request(String method,String path,String body,String key,boolean second,boolean auth) throws Exception {
        return authenticatedRequest(method,path,body,key,auth?runtime.token(second):null);
    }
    public Reply managementRequest(String method,String path,String body,String key,String identity) throws Exception {
        return authenticatedRequest(method,path,body,managementKey(runId,key),identity==null?null:runtime.operatorToken(identity));
    }
    /** Test runs share operator identities, so only valid client keys get a run-specific namespace. */
    public static String managementKey(String runId,String key) {
        if(key==null || !key.matches("[A-Za-z0-9._:-]{8,64}")) return key;
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest((runId+"|"+key).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private Reply authenticatedRequest(String method,String path,String body,String key,String token) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:18083"+path)).timeout(Duration.ofSeconds(15));
        if(token!=null){builder.header("Authorization","Bearer "+token);}
        if(key!=null){builder.header("Idempotency-Key",key);}
        if(body!=null){builder.header("Content-Type","application/json");builder.method(method,HttpRequest.BodyPublishers.ofString(body));}
        else{builder.method(method,HttpRequest.BodyPublishers.noBody());}
        var result=http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
        JsonNode parsed=json.readTree(result.body());
        log(Map.of("type","HTTP","method",method,"path",path,"status",result.statusCode(),"body",parsed));
        return new Reply(result.statusCode(),parsed,result.headers().map());
    }
    public Reply query(long id) throws Exception { return request("GET","/v1/booking-requests/"+id,null,null,false,true); }
    public Map<String,Object> snapshot(Fixture f) {
        Map<String,Object> data=new LinkedHashMap<>();
        data.put("fixture",f);
        data.put("R",jdbc.queryForList("SELECT * FROM booking_request WHERE session_id=? AND user_id IN (?,?) ORDER BY id",f.session(),f.userA(),f.userB()));
        data.put("O",jdbc.queryForList("SELECT * FROM booking_order WHERE session_id=? AND user_id IN (?,?) ORDER BY id",f.session(),f.userA(),f.userB()));
        data.put("Q",jdbc.queryForList("SELECT * FROM user_session_quota WHERE session_id=? AND user_id IN (?,?) ORDER BY user_id",f.session(),f.userA(),f.userB()));
        data.put("L",jdbc.queryForList("SELECT * FROM inventory_ledger WHERE tier_id IN (?,?) ORDER BY id",f.tier(),f.otherTier()));
        data.put("E",jdbc.queryForList("SELECT e.* FROM outbox_event e JOIN booking_request r ON r.id=e.booking_request_id WHERE r.session_id=? AND r.user_id IN (?,?) ORDER BY e.id",f.session(),f.userA(),f.userB()));
        data.put("P",jdbc.queryForList("SELECT * FROM payment_attempt WHERE session_id=? AND user_id IN (?,?) ORDER BY id",f.session(),f.userA(),f.userB()));
        data.put("PE",jdbc.queryForList("SELECT e.* FROM outbox_event e JOIN payment_attempt a ON a.id=e.payment_attempt_id WHERE a.session_id=? AND a.user_id IN (?,?) ORDER BY e.id",f.session(),f.userA(),f.userB()));
        data.put("operations",jdbc.queryForList("SELECT op.* FROM sim_payment_operation op JOIN payment_attempt a ON a.id=op.attempt_id WHERE a.session_id=? AND a.user_id IN (?,?) ORDER BY op.operation_id",f.session(),f.userA(),f.userB()));
        data.put("inventory",jdbc.queryForList("SELECT id,capacity,available FROM ticket_tier WHERE id IN (?,?) ORDER BY id",f.tier(),f.otherTier()));
        return data;
    }
    public void assertCounts(Fixture f,int requests,int orders,int ledger,int available) {
        assertEquals(requests,jdbc.queryForObject("SELECT COUNT(*) FROM booking_request WHERE session_id=?",Integer.class,f.session()));
        assertEquals(requests,jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event e JOIN booking_request r ON r.id=e.booking_request_id WHERE r.session_id=?",Integer.class,f.session()));
        assertEquals(orders,jdbc.queryForObject("SELECT COUNT(*) FROM booking_order WHERE session_id=?",Integer.class,f.session()));
        assertEquals(ledger,jdbc.queryForObject("SELECT COUNT(*) FROM inventory_ledger WHERE tier_id IN (?,?)",Integer.class,f.tier(),f.otherTier()));
        assertEquals(available,jdbc.queryForObject("SELECT available FROM ticket_tier WHERE id=?",Integer.class,f.tier()));
        int held=jdbc.queryForObject("SELECT COUNT(*) FROM booking_order WHERE session_id=? AND status IN ('HOLD','PAID')",Integer.class,f.session());
        assertEquals(held,jdbc.queryForObject("SELECT COUNT(*) FROM user_session_quota WHERE session_id=? AND owner_type='ORDER'",Integer.class,f.session()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM ticket_tier WHERE id IN (?,?) AND available<0",Integer.class,f.tier(),f.otherTier()));
        assertEquals(held,jdbc.queryForObject("SELECT SUM(capacity-available) FROM ticket_tier WHERE id IN (?,?)",Integer.class,f.tier(),f.otherTier()));
        assertEquals(-held,jdbc.queryForObject("SELECT COALESCE(SUM(delta),0) FROM inventory_ledger WHERE tier_id IN (?,?)",Integer.class,f.tier(),f.otherTier()));
        for(long tier:List.of(f.tier(),f.otherTier())) {
            assertEquals(jdbc.queryForObject("SELECT capacity-available FROM ticket_tier WHERE id=?",Integer.class,tier),
                jdbc.queryForObject("SELECT COUNT(*) FROM booking_order WHERE tier_id=? AND status IN ('HOLD','PAID')",Integer.class,tier));
        }
        for(var request:jdbc.queryForList("SELECT * FROM booking_request WHERE session_id=?",f.session())){
            long requestId=number(request,"id");
            var event=jdbc.queryForMap("SELECT * FROM outbox_event WHERE aggregate_id=? AND event_type='BOOKING_REQUESTED'",requestId);
            String status=(String)request.get("status");
            assertEquals(status.equals("ACCEPTED")?"READY":status.equals("PROCESSING_FAILED")?"DEAD":"DONE",event.get("state"));
            if(status.equals("SUCCEEDED")){
                var order=jdbc.queryForMap("SELECT * FROM booking_order WHERE request_id=?",requestId);
                assertEquals(number(request,"order_id"),number(order,"id"));
                boolean holding=Set.of("HOLD","PAID").contains(order.get("status"));
                Long owner=jdbc.queryForObject("SELECT owner_id FROM user_session_quota WHERE user_id=? AND session_id=?",Long.class,number(request,"user_id"),f.session());
                if("PAID".equals(order.get("status"))) {
                    assertNotNull(order.get("paid_at")); assertEquals(number(order,"current_attempt_id"),number(order,"paid_attempt_id"));
                    assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempt WHERE id=? AND order_id=? AND user_id=? AND settlement_status=\'APPLIED\' AND result_status=\'SUCCESS\' AND amount_cent=? AND currency=?",Integer.class,number(order,"paid_attempt_id"),number(order,"id"),number(order,"user_id"),number(order,"payable_cent"),order.get("currency")));
                }
                if(holding){
                    assertEquals(number(order,"id"),owner);assertNull(order.get("closed_at"));assertNull(order.get("close_reason"));
                }else{
                    assertNotEquals(number(order,"id"),owner);assertNotNull(order.get("closed_at"));
                    assertTrue(Set.of("CANCELLED","EXPIRED").contains(order.get("status")));
                    boolean cancelled="CANCELLED".equals(order.get("status"));
                    assertEquals(cancelled?"USER_CANCELLED":"PAYMENT_DEADLINE_EXCEEDED",order.get("close_reason"));
                    assertEquals(cancelled,instant(order.get("closed_at")).isBefore(instant(order.get("pay_expires_at"))));
                }
                assertEquals(holding?0:1,jdbc.queryForObject("SELECT COUNT(*) FROM inventory_ledger WHERE order_id=? AND action='RELEASE' AND delta=1",Integer.class,number(order,"id")));
                assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM inventory_ledger WHERE order_id=? AND action='RESERVE' AND delta=-1",Integer.class,number(order,"id")));
                assertEquals(number(order,"unit_price_cent"),number(order,"payable_cent"));assertEquals("CNY",order.get("currency"));assertEquals(1,number(order,"quantity"));
                Instant close=instant(jdbc.queryForObject("SELECT booking_close_at FROM event_session WHERE id=?",Object.class,f.session()));
                assertEquals(earlier(instant(order.get("hold_created_at")).plusSeconds(600),close),instant(order.get("pay_expires_at")));
                assertTrue(instant(order.get("hold_created_at")).isBefore(instant(request.get("process_deadline"))));
                assertNull(request.get("reason_code"));
            }else{
                assertNull(request.get("order_id"));
                assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM booking_order WHERE request_id=?",Integer.class,requestId));
                if(!status.equals("ACCEPTED")){assertNotNull(request.get("reason_code"));}
            }
        }
    }
    public void pass(Fixture f,String notes) throws Exception {
        save(f.name()+"-facts.json",snapshot(f));
        passed.add(f.name()); log(Map.of("case",f.name(),"result","passed","notes",notes));
    }
    public synchronized void log(Object value) throws Exception {
        Files.writeString(evidence.resolve("events.jsonl"),json.writeValueAsString(value)+System.lineSeparator(),StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
    public void save(String name,Object value) throws Exception { json.writeValue(evidence.resolve(name).toFile(),value); }
    public static void await(BooleanSupplier condition) throws Exception {
        long stop=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        while(!condition.getAsBoolean()){
            if(System.nanoTime()>stop){fail("bounded condition timed out");}
            Thread.sleep(25);
        }
    }
    private void cleanup(Fixture f) {
        assertEquals(runId+":"+f.name(),jdbc.queryForObject("SELECT title FROM event WHERE id=?",String.class,f.event()));
        assertEquals(runId+":"+f.name()+":a",jdbc.queryForObject("SELECT subject_key FROM app_user WHERE id=?",String.class,f.userA()));
        assertEquals(runId+":"+f.name()+":b",jdbc.queryForObject("SELECT subject_key FROM app_user WHERE id=?",String.class,f.userB()));
        var requests=jdbc.queryForList("SELECT id FROM booking_request WHERE session_id=? AND user_id IN (?,?)",Long.class,f.session(),f.userA(),f.userB());
        for(long id:requests){
            var orders=jdbc.queryForList("SELECT id FROM booking_order WHERE request_id=?",Long.class,id);
            for(long order:orders){
                var attempts=jdbc.queryForList("SELECT id FROM payment_attempt WHERE order_id=? AND user_id IN (?,?)",Long.class,order,f.userA(),f.userB());
                for(long attempt:attempts) {
                    jdbc.update("DELETE FROM sim_payment_operation WHERE attempt_id=?",attempt);
                    jdbc.update("DELETE FROM outbox_event WHERE payment_attempt_id=? AND event_type='PAYMENT_RESULT_RECORDED'",attempt);
                    one(jdbc.update("DELETE FROM payment_attempt WHERE id=? AND order_id=?",attempt,order));
                }
                jdbc.update("DELETE FROM inventory_ledger WHERE order_id=? AND tier_id IN (?,?)",order,f.tier(),f.otherTier());
            }
        }
        jdbc.update("DELETE FROM user_session_quota WHERE session_id=? AND user_id IN (?,?)",f.session(),f.userA(),f.userB());
        for(long id:requests){
            jdbc.update("DELETE FROM outbox_event WHERE aggregate_id=? AND event_type='BOOKING_REQUESTED'",id);
            jdbc.update("DELETE FROM booking_order WHERE request_id=? AND session_id=?",id,f.session());
            one(jdbc.update("DELETE FROM booking_request WHERE id=? AND session_id=?",id,f.session()));
        }
        one(jdbc.update("DELETE FROM ticket_tier WHERE id=? AND session_id=?",f.tier(),f.session()));
        one(jdbc.update("DELETE FROM ticket_tier WHERE id=? AND session_id=?",f.otherTier(),f.session()));
        one(jdbc.update("DELETE FROM event_session WHERE id=? AND event_id=?",f.session(),f.event()));
        one(jdbc.update("DELETE FROM event WHERE id=? AND title=?",f.event(),runId+":"+f.name()));
        one(jdbc.update("DELETE FROM app_user WHERE id=? AND subject_key=?",f.userA(),runId+":"+f.name()+":a"));
        one(jdbc.update("DELETE FROM app_user WHERE id=? AND subject_key=?",f.userB(),runId+":"+f.name()+":b"));
    }
    @Override public void close() throws Exception {
        runtime.recovery=false;
        try {
            save("redis-key-manifest.json",runtime.observedRedisKeys);
            if(cleanOnClose && app!=null){
                var redis=app.getBean(org.springframework.data.redis.core.StringRedisTemplate.class);
                for(String key:runtime.observedRedisKeys){
                    if(!key.startsWith("ticketing:biz01:it:"+runId+":rl:") || !key.matches(".*:rl:\\{[1-9][0-9]*\\}:(u:[1-9][0-9]*|t):[0-9]+")){throw new IllegalStateException("Unexpected Redis key");}
                    redis.delete(key);
                    assertFalse(Boolean.TRUE.equals(redis.hasKey(key)));
                }
            }
            if(cleanOnClose){for(Fixture f:fixtures){cleanup(f);}}
            save("completion.json",Map.of("runId",runId,"passedCases",passed,"cleaned",cleanOnClose,"fixtureCount",fixtures.size()));
        }finally{stopApp(); probe.close();if(demo)runtime.leaveDemo();}
    }
}
