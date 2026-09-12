package com.ticketing.booking.demo;

import com.ticketing.booking.config.Bootstrap;
import com.ticketing.booking.fixture.BookingHarness;
import com.ticketing.booking.fixture.BookingHarness.Fixture;
import com.ticketing.booking.fixture.BookingHarness.Reply;
import com.ticketing.booking.service.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static com.ticketing.booking.domain.Values.*;

/** One local test session. No public endpoint exposes fixture creation, clock or failure controls. */
public final class DemoSession implements AutoCloseable {
    public final BookingHarness h;
    private final Path privateDirectory;
    private final Path evidence;
    private final Map<Long,Map<String,String>> identities=new LinkedHashMap<>();
    private final Map<String,Long> cursors=new HashMap<>();
    private final Map<String,List<Map<String,Object>>> priorFacts;
    private static final Map<String,String> TABLE_KEYS=Map.ofEntries(
        Map.entry("app_user","id"),Map.entry("event","id"),Map.entry("event_session","id"),
        Map.entry("ticket_tier","id"),Map.entry("booking_request","id"),Map.entry("booking_order","id"),
        Map.entry("user_session_quota","user_id,session_id"),Map.entry("inventory_ledger","id"),
        Map.entry("payment_attempt","id"),Map.entry("outbox_event","id"),
        Map.entry("sim_payment_operation","operation_id"),Map.entry("flyway_schema_history","installed_rank"));

    public DemoSession(String runId) throws Exception {
        privateDirectory=DemoFiles.directory(runId);
        evidence=Bootstrap.ROOT.resolve("evidence/DEL-01-I-001").resolve(runId);
        if(Files.exists(evidence)) throw new IllegalStateException("USED_DEMO_EVIDENCE");
        Files.createDirectories(evidence);
        // This resets direct/recovery BEFORE Spring starts and forces both off for the whole demo lifetime.
        System.setProperty("biz.evidenceTask","BIZ-04-I-001");
        h=new BookingHarness(runId,true,true);
        try {
            if(h.runtime.directEnabled()||h.runtime.recoveryEnabled()) throw new IllegalStateException("GLOBAL_WORKER_NOT_DISABLED");
            priorFacts=snapshotAll();
            save("prior-facts.json",priorFacts);
        } catch(Exception failure) { h.close();throw failure; }
    }
    public Path evidence() { return evidence; }
    public Instant now() {
        return h.runtime.fixedTime!=null?h.runtime.fixedTime:h.jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)",java.sql.Timestamp.class).toInstant();
    }
    public void setTime(Instant time) { h.runtime.fixedTime=time; }
    public void plan(String outcome,long delayMs) { h.runtime.plan=new com.ticketing.booking.config.RuntimeServices.SimulationPlan(outcome,delayMs); }
    public Fixture fixture(String name,int capacity) throws Exception {
        if(h.fixtures.stream().anyMatch(f->f.name().equals(name))) throw new IllegalArgumentException("DUPLICATE_DEMO_FIXTURE");
        Instant actual=h.jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)",java.sql.Timestamp.class).toInstant();
        var transaction=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(h.probe));
        transaction.setTimeout(8);
        Fixture f=transaction.execute(status -> {
            try { return h.fixtureAt(name,capacity,actual); }
            catch(Exception failure) { throw new IllegalStateException("DEMO_FIXTURE_FAILED",failure); }
        });
        identities.put(f.session(),h.runtime.registerDemoFixture(h.jdbc,f));
        DemoFiles.write(privateDirectory.resolve("fixtures.json"),Map.of("runId",h.runId,"fixtures",h.fixtures,"identities",identities));
        return f;
    }
    public Reply request(Fixture fixture,String method,String path,String body,String key,boolean second,boolean authenticated) throws Exception {
        var identity=identities.get(fixture.session());
        if(identity==null || !h.fixtures.contains(fixture)) throw new IllegalArgumentException("FOREIGN_DEMO_FIXTURE");
        if(!path.startsWith("/v1/")||path.contains("://")||path.contains("#")) throw new IllegalArgumentException("INVALID_DEMO_HTTP_PATH");
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:18083"+path)).timeout(Duration.ofSeconds(15));
        if(authenticated) builder.header("Authorization","Bearer "+identity.get(second?"userB":"userA"));
        if(key!=null) builder.header("Idempotency-Key",key);
        if(body!=null) builder.header("Content-Type","application/json");
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        var response=h.http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
        var parsed=h.json.readTree(response.body());
        log(Map.of("type","HTTP","method",method,"path",path,"requestBody",body==null?"":body,"status",response.statusCode(),"response",parsed));
        return new Reply(response.statusCode(),parsed,response.headers().map());
    }
    public Reply managementRequest(String method,String path,String body,String key,String identity) throws Exception {
        for(var fixture:List.copyOf(h.fixtures)) h.runtime.enrollManagementFixture(h.jdbc,fixture);
        var reply=h.managementRequest(method,path,body,key,identity);
        log(Map.of("type","MANAGEMENT_HTTP","method",method,"path",path,"status",reply.status(),"response",reply.body()));
        return reply;
    }
    /** Each candidate query is anchored to an immutable server-created fixture, never to a client ID list. */
    public void driveOnce() throws Exception {
        if(h.runtime.directEnabled()||h.runtime.recoveryEnabled()) throw new IllegalStateException("GLOBAL_WORKER_ENABLED");
        for(var f:List.copyOf(h.fixtures)) {
            h.runtime.enrollManagementFixture(h.jdbc,f); // rechecks database ownership before any work
            String route=" session_id=? AND user_id IN (?,?) AND tier_id IN (?,?) ";
            Object[] args={f.session(),f.userA(),f.userB(),f.tier(),f.otherTier()};
            for(long id:candidates(f,"allocate","SELECT id FROM booking_request WHERE"+route+"AND status='ACCEPTED'",args))
                h.app.getBean(AllocationService.class).process(id);
            for(long id:candidates(f,"expire","SELECT id FROM booking_order WHERE"+route+"AND status='HOLD'",args))
                h.app.getBean(OrderCloseService.class).expire(id);
            String payment="SELECT a.id FROM payment_attempt a JOIN booking_order o ON o.id=a.order_id AND o.user_id=a.user_id AND o.session_id=a.session_id WHERE a.session_id=? AND a.user_id IN (?,?) AND o.tier_id IN (?,?)";
            for(long id:candidates(f,"generate",payment+" AND a.result_status='PENDING'",args))
                h.app.getBean(PaymentService.class).generate(id);
            h.runtime.enrollManagementFixture(h.jdbc,f);
            for(long id:candidates(f,"settle",payment+" AND a.settlement_status='UNRESOLVED'",args))
                h.app.getBean(PaymentService.class).settle(id);
        }
    }
    private List<Long> candidates(Fixture f,String phase,String query,Object[] scope) {
        String cursorKey=f.session()+":"+phase;long cursor=cursors.getOrDefault(cursorKey,0L);
        String id=(phase.equals("settle")||phase.equals("generate"))?"a.id":"id";
        Object[] args=Arrays.copyOf(scope,scope.length+1);args[scope.length]=cursor;
        var ids=h.jdbc.queryForList(query+" AND "+id+">? ORDER BY "+id+" LIMIT 20",Long.class,args);
        cursors.put(cursorKey,ids.size()<20?0L:ids.get(ids.size()-1));return ids;
    }
    private Map<String,List<Map<String,Object>>> snapshotAll() {
        var result=new TreeMap<String,List<Map<String,Object>>>();
        h.jdbc.setQueryTimeout(5);
        for(var table:TABLE_KEYS.entrySet()) result.put(table.getKey(),h.jdbc.queryForList("SELECT * FROM "+table.getKey()+" ORDER BY "+table.getValue()));
        return result;
    }
    private String key(String table,Map<String,Object> row) {
        var parts=new ArrayList<>();for(String column:TABLE_KEYS.get(table).split(","))parts.add(row.get(column));
        return h.json.valueToTree(parts).toString();
    }
    public void verifyPriorFactsUnchanged() throws Exception {
        var after=snapshotAll();
        for(var table:priorFacts.entrySet()) {
            var indexed=new HashMap<String,Map<String,Object>>();
            for(var row:after.get(table.getKey())) indexed.put(key(table.getKey(),row),row);
            for(var old:table.getValue()) {
                if(!h.json.valueToTree(old).equals(h.json.valueToTree(indexed.get(key(table.getKey(),old)))))
                    throw new IllegalStateException("PRIOR_FACT_CHANGED:"+table.getKey());
            }
        }
        save("prior-facts-check.json",Map.of("oldRowsUnchanged",true,"checkedAt",Instant.now().toString(),"scope","all rows present before this demo","fixtureDataRetained",true));
    }
    public void save(String name,Object value) throws Exception {
        if(!name.matches("[A-Za-z0-9-]+\\.json")) throw new IllegalArgumentException("INVALID_EVIDENCE_NAME");
        h.json.writeValue(evidence.resolve(name).toFile(),value);
    }
    private void log(Object value) throws Exception {
        Files.writeString(evidence.resolve("http.jsonl"),h.json.writeValueAsString(value)+System.lineSeparator(),StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
    @Override public void close() throws Exception {
        try { verifyPriorFactsUnchanged(); }
        finally { h.cleanOnClose=false;h.close(); }
    }
}
