package com.ticketing.booking.integration;

import com.ticketing.booking.fixture.BookingHarness;
import com.ticketing.booking.fixture.BookingHarness.Fixture;
import com.ticketing.booking.fixture.BookingHarness.Reply;
import com.ticketing.booking.config.Bootstrap;
import com.ticketing.booking.domain.ApiFailure;
import com.ticketing.booking.service.AllocationService;
import com.ticketing.booking.service.EntryLimiter;
import com.ticketing.booking.recovery.RecoveryScanner;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import static com.ticketing.booking.fixture.BookingHarness.BASE;
import static com.ticketing.booking.domain.Values.*;
import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BookingRegressionTest {
    BookingHarness h;
    @BeforeAll void open() throws Exception { h=new BookingHarness(System.getProperty("biz.runId","biz01-regression-001")); }
    @AfterAll void close() throws Exception { if(h!=null){h.cleanOnClose=h.passed.size()==19; h.close();} }
    interface Scenario { void run() throws Exception; }
    @TestFactory Stream<DynamicTest> scenarios() {
        List<Scenario> cases=List.of(this::c01,this::c02,this::c03,this::c04,this::c05,this::c06,
            this::c07,this::c08,this::c09,this::c10,this::c11,this::c12,this::c13,this::c14,this::c15,
            this::c16,this::c17,this::c18,this::c19);
        return java.util.stream.IntStream.range(0,cases.size()).mapToObj(i->DynamicTest.dynamicTest(
            String.format("C%02d",i+1),()->cases.get(i).run()));
    }
    void done(String code,String notes,Fixture... fixtures) throws Exception {
        for(Fixture f:fixtures){h.save(f.name()+"-facts.json",h.snapshot(f));}
        h.passed.add(code); h.log(Map.of("case",code,"result","passed","notes",notes));
    }
    String state(long id) throws Exception { return h.query(id).body().path("status").asText(); }
    String reason(long id) throws Exception { return h.query(id).body().path("reasonCode").asText(); }
    static void waitLatch(CountDownLatch latch) {
        try{if(!latch.await(10,TimeUnit.SECONDS)){throw new IllegalStateException("Test barrier timed out");}}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
    }
    static void barrier(CyclicBarrier barrier) {
        try{barrier.await(10,TimeUnit.SECONDS);}catch(Exception e){throw new IllegalStateException(e);}
    }
    List<Reply> concurrent(List<Callable<Reply>> calls) throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(calls.size());
        try{
            List<Future<Reply>> futures=new ArrayList<>(); for(var call:calls){futures.add(pool.submit(call));}
            List<Reply> result=new ArrayList<>(); for(var f:futures){result.add(f.get(20,TimeUnit.SECONDS));}
            return result;
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));h.runtime.hooks.clear();}
    }
    AllocationService allocation(){return h.app.getBean(AllocationService.class);}
    RecoveryScanner scanner(){return h.app.getBean(RecoveryScanner.class);}

    void c01() throws Exception {
        var f=h.fixture("C01",1);var r=h.post(f,"c01-single-key");assertEquals(202,r.status());
        var q=h.query(r.id());assertEquals("SUCCEEDED",q.body().path("status").asText());
        var order=q.body().path("order");assertEquals("HOLD",order.path("orderStatus").asText());
        assertEquals(9900,order.path("unitPriceCent").asInt());assertEquals(9900,order.path("payableCent").asInt());
        assertEquals("CNY",order.path("currency").asText());assertEquals(1,order.path("quantity").asInt());
        assertEquals(time(BASE.plusSeconds(600)),order.path("payExpiresAt").asText());
        h.assertCounts(f,1,1,1,0);done("C01","real HTTP/SQL successful single-ticket HOLD",f);
    }
    void c02() throws Exception {
        var f=h.fixture("C02",0);var r=h.post(f,"c02-no-tickets");assertEquals(202,r.status());
        assertEquals("REJECTED",state(r.id()));assertEquals("NO_TICKETS",reason(r.id()));
        h.assertCounts(f,1,0,0,0);done("C02","no inventory: durable business rejection",f);
    }
    void c03() throws Exception {
        var a=h.fixture("C03-seq",1);var first=h.post(a,"c03-same-key");
        assertEquals(202,first.status());assertEquals(first.body(),h.post(a,"c03-same-key").body());
        h.runtime.fixedTime=BASE.plusSeconds(4000);assertEquals(first.body(),h.post(a,"c03-same-key").body());
        h.assertCounts(a,1,1,1,0);
        var b=h.fixture("C03-race",1);var gate=new CyclicBarrier(4);
        h.runtime.hooks.put("AFTER_IDEMPOTENCY_MISS",id->barrier(gate));
        var replies=concurrent(List.of(()->h.post(b,"c03-concurrent"),()->h.post(b,"c03-concurrent"),
            ()->h.post(b,"c03-concurrent"),()->h.post(b,"c03-concurrent")));
        for(var r:replies){assertEquals(202,r.status());assertEquals(replies.get(0).body(),r.body());}
        String reordered="{\"quantity\":1,\"tierId\":\""+b.tier()+"\",\"sessionId\":\""+b.session()+"\"}";
        assertEquals(replies.get(0).body(),h.request("POST","/v1/booking-requests",reordered,"c03-concurrent",false,true).body());
        h.assertCounts(b,1,1,1,0);done("C03","persisted retries including after close, reordered JSON, four simultaneous first requests",a,b);
    }
    void c04() throws Exception {
        var f=h.fixture("C04",1);var first=h.post(f,"c04-same-key");assertEquals(202,first.status());
        String different=h.input(f).replace("\"tierId\":\""+f.tier()+"\"","\"tierId\":\""+f.otherTier()+"\"");
        var conflict=h.request("POST","/v1/booking-requests",different,"c04-same-key",false,true);
        assertEquals(409,conflict.status());assertEquals("IDEMPOTENCY_CONFLICT",conflict.body().path("code").asText());
        h.assertCounts(f,1,1,1,0);done("C04","same key changed tier: conflict, previous facts unchanged",f);
    }
    void c05() throws Exception {
        List<Fixture> used=new ArrayList<>();
        for(boolean cross:List.of(false,true)){
            var f=h.fixture(cross?"C05-cross":"C05-same",1);used.add(f);var gate=new CyclicBarrier(2);
            h.runtime.hooks.put("BEFORE_T1",id->barrier(gate));
            String second=cross?h.input(f).replace("\"tierId\":\""+f.tier()+"\"","\"tierId\":\""+f.otherTier()+"\""):h.input(f);
            var replies=concurrent(List.of(()->h.post(f,"c05-first-key"),()->h.request("POST","/v1/booking-requests",second,"c05-second-key",false,true)));
            for(var r:replies){assertEquals(202,r.status());}
            var states=new ArrayList<String>();for(var r:replies){states.add(state(r.id()));}
            assertEquals(1,Collections.frequency(states,"SUCCEEDED"));assertEquals(1,Collections.frequency(states,"REJECTED"));
            for(var r:replies){if("REJECTED".equals(state(r.id()))){assertEquals("QUOTA_ALREADY_HELD",reason(r.id()));}}
            int available=h.jdbc.queryForObject("SELECT available FROM ticket_tier WHERE id=?",Integer.class,f.tier());
            h.assertCounts(f,2,1,1,available);
            assertEquals(1,h.jdbc.queryForObject("SELECT SUM(available) FROM ticket_tier WHERE id IN (?,?)",Integer.class,f.tier(),f.otherTier()));
        }
        done("C05","same user new keys, same-tier and cross-tier barriers: one quota owner",used.toArray(Fixture[]::new));
    }
    void c06() throws Exception {
        var f=h.fixture("C06",1);var gate=new CyclicBarrier(2);
        h.runtime.hooks.put("BEFORE_T1",id->barrier(gate));
        var replies=concurrent(List.of(()->h.post(f,"c06-user-a-key"),()->h.request("POST","/v1/booking-requests",h.input(f),"c06-user-b-key",true,true)));
        for(var r:replies){assertEquals(202,r.status());}
        var rows=h.jdbc.queryForList("SELECT status,reason_code FROM booking_request WHERE session_id=?",f.session());
        assertEquals(1,rows.stream().filter(r->"SUCCEEDED".equals(r.get("status"))).count());
        assertEquals(1,rows.stream().filter(r->"NO_TICKETS".equals(r.get("reason_code"))).count());
        h.assertCounts(f,2,1,1,0);done("C06","two authenticated users contend at a barrier for the last ticket",f);
    }
    Process child(Fixture f,String mode) throws Exception {
        String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        String options="-Xmx256m\n-Dfile.encoding=UTF-8\n-cp\n\""+cp.replace('\\','/')+"\"\ncom.ticketing.booking.fixture.RecoveryProcess\n"+h.runId+"\n"+mode+"\n"+f.userA()+"\n"+f.userB()+"\n";
        Path args=h.evidence.resolve("C07-"+mode+"-args.txt");Files.writeString(args,options);
        String java=Path.of(System.getProperty("java.home"),"bin","java.exe").toString();
        ProcessBuilder builder=new ProcessBuilder(java,"@"+args).directory(Bootstrap.ROOT.toFile());
        builder.redirectErrorStream(true).redirectOutput(h.evidence.resolve("C07-"+mode+"-process.log").toFile());
        Process process=builder.start();h.log(Map.of("process",mode,"pid",process.pid(),"port",18083));
        try{BookingHarness.await(()->Files.exists(h.evidence.resolve("C07-"+mode+"-ready.json"))||!process.isAlive());
            assertTrue(process.isAlive(),"child exited during startup");
        }catch(Throwable failure){if(process.isAlive()){process.destroyForcibly().waitFor(5,TimeUnit.SECONDS);}throw failure;}
        return process;
    }
    void stopChild(Process process) throws Exception {
        if(process!=null && process.isAlive()){
            process.getOutputStream().write("STOP\n".getBytes(StandardCharsets.UTF_8));process.getOutputStream().flush();
            if(!process.waitFor(8,TimeUnit.SECONDS)){process.destroyForcibly().waitFor(5,TimeUnit.SECONDS);fail("owned child did not stop gracefully");}
            assertEquals(0,process.exitValue());
        }
    }
    void c07() throws Exception {
        var f=h.fixture("C07",1);h.stopApp();Process crash=null,recover=null;
        try{
            crash=child(f,"crash");
            Exception noResponse=assertThrows(Exception.class,()->h.post(f,"c07-crash-key"));
            assertTrue(crash.waitFor(10,TimeUnit.SECONDS));assertEquals(71,crash.exitValue());
            var committed=h.json.readTree(Files.readString(h.evidence.resolve("C07-crash-committed.json")));
            for(var key:committed.path("redisKeys")){h.runtime.observedRedisKeys.add(key.asText());}
            long request=committed.path("requestId").asLong();
            assertEquals("ACCEPTED",h.jdbc.queryForObject("SELECT status FROM booking_request WHERE id=?",String.class,request));
            h.assertCounts(f,1,0,0,1);h.save("C07-before-restart.json",h.snapshot(f));
            h.log(Map.of("case","C07","clientOutcome","no HTTP response after committed T1","exceptionType",noResponse.getClass().getSimpleName(),"exitCode",71,"requestId",request));
            recover=child(f,"recover");
            BookingHarness.await(()->"SUCCEEDED".equals(h.jdbc.queryForObject("SELECT status FROM booking_request WHERE id=?",String.class,request)));
            assertEquals("SUCCEEDED",state(request));assertEquals(202,h.post(f,"c07-crash-key").status());
            h.assertCounts(f,1,1,1,0);done("C07","real process halt(71) after committed T1; new PID resumes with READY scanner, one HOLD",f);
        }finally{stopChild(recover);stopChild(crash);h.startApp();}
    }
    void c08() throws Exception {
        var f=h.fixture("C08",1);CountDownLatch committed=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicLong request=new AtomicLong();CyclicBarrier both=new CyclicBarrier(2);
        h.runtime.hooks.put("T1_COMMITTED",id->{request.set(id);committed.countDown();waitLatch(release);});
        h.runtime.hooks.put("BEFORE_T2",id->{if(id==request.get()){barrier(both);}});
        ExecutorService executor=Executors.newFixedThreadPool(2);
        try{
            Future<Reply> direct=executor.submit(()->h.post(f,"c08-repeat-key"));waitLatch(committed);
            Future<?> scan=executor.submit(()->scanner().scanOnce());release.countDown();
            assertEquals(202,direct.get(15,TimeUnit.SECONDS).status());scan.get(15,TimeUnit.SECONDS);
        }finally{release.countDown();h.runtime.hooks.clear();executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
        String expiry=h.query(request.get()).body().path("order").path("payExpiresAt").asText();
        h.runtime.fixedTime=BASE.plusSeconds(30);allocation().process(request.get());scanner().scanOnce();
        assertEquals(expiry,h.query(request.get()).body().path("order").path("payExpiresAt").asText());
        h.assertCounts(f,1,1,1,0);done("C08","direct T2 and scanner coordinated at the same processing boundary; repeat is a no-op",f);
    }
    void c09() throws Exception {
        List<Fixture> used=new ArrayList<>();
        for(String point:List.of("AFTER_STOCK","AFTER_ORDER","AFTER_LEDGER")){
            var f=h.fixture("C09-"+point.toLowerCase(Locale.ROOT),1);used.add(f);
            h.runtime.hooks.put(point,id->{throw new IllegalStateException("INJECTED_"+point);});
            var accepted=h.post(f,"c09-rollback-key");assertEquals(202,accepted.status());
            assertEquals("ACCEPTED",state(accepted.id()));h.assertCounts(f,1,0,0,1);
            assertEquals(1,h.jdbc.queryForObject("SELECT attempts FROM outbox_event WHERE aggregate_id=?",Integer.class,accepted.id()));
            h.save(f.name()+"-rolled-back.json",h.snapshot(f));h.runtime.hooks.clear();
            h.runtime.fixedTime=instant(h.jdbc.queryForObject("SELECT next_attempt_at FROM outbox_event WHERE aggregate_id=?",Object.class,accepted.id()));
            scanner().scanOnce();assertEquals("SUCCEEDED",state(accepted.id()));h.assertCounts(f,1,1,1,0);
        }
        done("C09","three actual write-stage exceptions; independent SQL proves rollback before retry, then one success",used.toArray(Fixture[]::new));
    }
    void c10() throws Exception {
        var f=h.fixture("C10-wire",1);CountDownLatch committed=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicLong id=new AtomicLong();
        h.runtime.hooks.put("BEFORE_ACCEPT_RESPONSE",request->{id.set(request);committed.countDown();waitLatch(release);});
        try(Socket wire=new Socket()){
            wire.connect(new InetSocketAddress("127.0.0.1",18083),3000);wire.setSoTimeout(3000);
            String body=h.input(f);
            String request="POST /v1/booking-requests HTTP/1.1\r\nHost: 127.0.0.1:18083\r\nAuthorization: Bearer "+h.runtime.token(false)+"\r\nIdempotency-Key: c10-wire-key\r\nContent-Type: application/json\r\nContent-Length: "+body.getBytes(StandardCharsets.UTF_8).length+"\r\nConnection: close\r\n\r\n"+body;
            wire.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));wire.getOutputStream().flush();waitLatch(committed);
            assertEquals("SUCCEEDED",h.jdbc.queryForObject("SELECT status FROM booking_request WHERE id=?",String.class,id.get()));
            h.save("C10-committed-before-disconnect.json",h.snapshot(f));wire.setSoLinger(true,0);wire.close();
            h.log(Map.of("case","C10","point","after physical T1 and T2 commits, before server response write","clientAction","TCP reset via SO_LINGER(0)","requestId",id.get()));
        }finally{h.runtime.hooks.clear();release.countDown();}
        var retry=h.post(f,"c10-wire-key");assertEquals(202,retry.status());assertEquals(id.get(),retry.id());h.assertCounts(f,1,1,1,0);
        var unknown=h.fixture("C10-unknown",1);
        h.runtime.hooks.put("T1_COMMITTED",request->{throw new ApiFailure(503,"ACCEPTANCE_UNKNOWN");});
        assertEquals("ACCEPTANCE_UNKNOWN",h.post(unknown,"c10-unknown-key").body().path("code").asText());
        h.runtime.hooks.clear();var retried=h.post(unknown,"c10-unknown-key");assertEquals(202,retried.status());scanner().scanOnce();
        assertEquals("SUCCEEDED",state(retried.id()));h.assertCounts(unknown,1,1,1,0);
        done("C10","real TCP response loss after commits; separate application-level unknown-ack injection after T1 (not a claimed wire-level DB fault)",f,unknown);
    }
    void c11() throws Exception {
        var outside=h.fixture("C11-outside",1);h.runtime.fixedTime=BASE.minusSeconds(61);
        assertEquals(409,h.post(outside,"c11-before-open").status());h.runtime.fixedTime=BASE.plusSeconds(3600);
        assertEquals(409,h.post(outside,"c11-at-close").status());h.assertCounts(outside,0,0,0,1);
        var locked=h.fixture("C11-lock",1);h.runtime.direct=false;var accepted=h.post(locked,"c11-lock-boundary");assertEquals(202,accepted.status());
        CountDownLatch reached=new CountDownLatch(1);h.runtime.hooks.put("T2_SESSION_LOCKED",id->{if(id==accepted.id()){reached.countDown();}});
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try(var connection=h.probe.getConnection()){
            connection.setAutoCommit(false);
            try(var sql=connection.prepareStatement("SELECT * FROM ticket_tier WHERE id=? FOR UPDATE")){
                sql.setLong(1,locked.tier());try(var rows=sql.executeQuery()){assertTrue(rows.next());}
            }
            Future<?> allocation=executor.submit(()->allocation().process(accepted.id()));waitLatch(reached);
            BookingHarness.await(()->h.jdbc.queryForList("SHOW PROCESSLIST").stream().anyMatch(row->{
                Object info=row.get("Info");return info!=null&&info.toString().contains("ticket_tier")&&info.toString().contains("FOR UPDATE");}));
            h.save("C11-lock-observed.json",Map.of("requestId",accepted.id(),"sameAccountBlockedTierStatementObserved",true));
            h.runtime.fixedTime=BASE.plusSeconds(120);connection.rollback();allocation.get(12,TimeUnit.SECONDS);
        }finally{h.runtime.hooks.clear();executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
        assertEquals("EXPIRED",state(accepted.id()));h.assertCounts(locked,1,0,0,1);
        done("C11","new-window rejection plus actual MySQL tier-lock wait crossing original processing deadline",outside,locked);
    }
    void c12() throws Exception {
        var f=h.fixture("C12",1);
        h.jdbc.update("UPDATE event_session SET booking_close_at=? WHERE id=?",sqlTime(BASE.plusSeconds(180)),f.session());
        h.runtime.hooks.put("T1_COMMITTED",id->h.runtime.fixedTime=BASE.plusSeconds(60));
        var r=h.post(f,"c12-short-window");h.runtime.hooks.clear();assertEquals(202,r.status());
        var order=h.query(r.id()).body().path("order");
        assertEquals(time(BASE.plusSeconds(60)),order.path("holdCreatedAt").asText());
        assertEquals(time(BASE.plusSeconds(180)),order.path("payExpiresAt").asText());
        assertEquals(time(BASE.plusSeconds(120)),r.body().path("processDeadline").asText());
        h.runtime.fixedTime=BASE.plusSeconds(100);assertEquals(r.body(),h.post(f,"c12-short-window").body());
        assertEquals(order,h.query(r.id()).body().path("order"));h.assertCounts(f,1,1,1,0);
        done("C12","hold time differs from acceptance, min(hold+600,close), immutable retry deadline",f);
    }
    void c13() throws Exception {
        var f=h.fixture("C13",1);
        assertEquals(401,h.request("POST","/v1/booking-requests",h.input(f),"c13-no-identity",false,false).status());
        var forged=java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:18083/v1/booking-requests"))
            .timeout(Duration.ofSeconds(5)).header("X-User-Id",Long.toString(f.userA())).header("Authorization","Bearer invalid-fixture-token")
            .header("Idempotency-Key","c13-forged-user").header("Content-Type","application/json")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(h.input(f))).build();
        var denied=h.http.send(forged,java.net.http.HttpResponse.BodyHandlers.ofString());assertEquals(401,denied.statusCode());
        h.log(Map.of("case","C13","forgedHeaderStatus",denied.statusCode(),"body",h.json.readTree(denied.body())));h.assertCounts(f,0,0,0,1);
        var own=h.post(f,"c13-owner-key");assertEquals(202,own.status());
        var hidden=h.request("GET","/v1/booking-requests/"+own.id(),null,null,true,true);
        var missing=h.request("GET","/v1/booking-requests/9223372036854775807",null,null,true,true);
        assertEquals(404,hidden.status());assertEquals(404,missing.status());
        assertEquals(hidden.body().path("code"),missing.body().path("code"));assertEquals(hidden.body().path("message"),missing.body().path("message"));
        assertFalse(hidden.body().has("requestId"));h.assertCounts(f,1,1,1,0);done("C13","identity precedes parsing, arbitrary identity header rejected, other-user and absent IDs indistinguishable",f);
    }
    void c14() throws Exception {
        var f=h.fixture("C14-limit",1);for(int i=0;i<5;i++){assertEquals(202,h.post(f,"c14-entry-key-"+i).status());}
        var limited=h.post(f,"c14-entry-key-5");assertEquals(429,limited.status());assertTrue(limited.headers().containsKey("retry-after"));
        h.assertCounts(f,5,1,1,0);
        h.runtime.hooks.put("BEFORE_REDIS",id->{throw new IllegalStateException("INJECTED_CLIENT_UNAVAILABLE");});
        assertEquals(202,h.post(f,"c14-entry-key-0").status());assertEquals(503,h.post(f,"c14-client-down").status());h.assertCounts(f,5,1,1,0);
        var bad=h.fixture("C14-type",1);var redis=h.app.getBean(StringRedisTemplate.class);
        var keys=h.app.getBean(EntryLimiter.class).keys(bad.userA(),bad.tier(),BASE);h.runtime.observedRedisKeys.addAll(keys);
        redis.opsForList().rightPush(keys.get(1),"wrong-counter-type");redis.expire(keys.get(1),Duration.ofSeconds(10));
        assertEquals(503,h.post(bad,"c14-bad-key-type").status());assertFalse(Boolean.TRUE.equals(redis.hasKey(keys.get(0))));
        h.save("C14-invalid-type-redis.json",Map.of("userKeyAbsent",true,"tierType",redis.type(keys.get(1)).code()));
        redis.delete(keys.get(1));redis.opsForValue().set(keys.get(1),"50",Duration.ofSeconds(10));
        assertEquals(429,h.post(bad,"c14-tier-limit").status());assertFalse(Boolean.TRUE.equals(redis.hasKey(keys.get(0))));
        h.assertCounts(bad,0,0,0,1);done("C14","real Lua user/tier caps and invalid key type; injected local-client outage without stopping Redis",f,bad);
    }
    void c15() throws Exception {
        var f=h.fixture("C15-six",1);h.runtime.hooks.put("AFTER_STOCK",id->{throw new IllegalStateException("INJECTED_RETRY_FAILURE");});
        var r=h.post(f,"c15-six-attempts");assertEquals(202,r.status());
        for(int i=1;i<=6;i++){
            assertEquals(i,h.jdbc.queryForObject("SELECT attempts FROM outbox_event WHERE aggregate_id=?",Integer.class,r.id()));
            h.assertCounts(f,1,0,0,1);h.save("C15-attempt-"+i+".json",h.snapshot(f));
            if(i<6){
                Instant next=instant(h.jdbc.queryForObject("SELECT next_attempt_at FROM outbox_event WHERE aggregate_id=?",Object.class,r.id()));
                long wait=Duration.between(h.runtime.fixedTime,next).toMillis(),base=1000L<<(i-1);
                assertTrue(wait>=base&&wait<=base+base/5);h.runtime.fixedTime=next;scanner().scanOnce();
            }
        }
        assertEquals("PROCESSING_FAILED",state(r.id()));
        assertEquals("DEAD",h.jdbc.queryForObject("SELECT state FROM outbox_event WHERE aggregate_id=?",String.class,r.id()));
        h.runtime.hooks.clear();h.runtime.fixedTime=BASE.plusSeconds(119);scanner().scanOnce();assertEquals("PROCESSING_FAILED",state(r.id()));
        var expired=h.fixture("C15-expiry",1);
        h.runtime.hooks.put("AFTER_STOCK",id->{h.runtime.fixedTime=BASE.plusSeconds(120);throw new IllegalStateException("INJECTED_AT_DEADLINE");});
        var x=h.post(expired,"c15-expiry-key");h.runtime.hooks.clear();assertEquals(202,x.status());assertEquals("EXPIRED",state(x.id()));
        h.assertCounts(expired,1,0,0,1);done("C15","six persisted bounded backoffs then DEAD, no automatic revival; expiry wins after rollback",f,expired);
    }
    void c16() throws Exception {
        var f=h.fixture("C16",1);String valid=h.input(f);
        for(String body:List.of(valid.replace("\"quantity\":1","\"quantity\":2"),valid.replace("}",",\"userId\":\"1\"}"),
            valid.replace("}",",\"price\":1}"),valid.replace("}",",\"time\":\"2030-01-01\"}"),valid.replace("\"quantity\":1","\"quantity\":4294967297"),
            valid.replace("\"sessionId\":\""+f.session()+"\"","\"sessionId\":\"9223372036854775808\""),
            valid.replace("\"quantity\":1","\"quantity\":1,\"quantity\":1"))){
            assertEquals(400,h.request("POST","/v1/booking-requests",body,"c16-invalid-key",false,true).status());
        }
        assertEquals(400,h.request("GET","/v1/booking-requests/01",null,null,false,true).status());h.assertCounts(f,0,0,0,1);
        // Explicit boundary fixture advances this isolated table's generated IDs; not a business endpoint or ALTER.
        long sentinel=h.jdbc.queryForObject("SELECT GREATEST(9007199254740993,COALESCE(MAX(id),0)+1) FROM booking_request",Long.class);
        byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(("v1|booking|user="+f.userA()+"|session="+f.session()+"|tier="+f.tier()+"|quantity=1").getBytes(StandardCharsets.UTF_8));
        h.jdbc.update("INSERT INTO booking_request(id,user_id,session_id,tier_id,quantity,idempotency_key,payload_hash,status,reason_code,accepted_at,process_deadline,finished_at) VALUES(?,?,?,?,1,'fixture-high-id',?,'EXPIRED','PROCESS_DEADLINE_EXCEEDED',?,?,?)",
            sentinel,f.userA(),f.session(),f.tier(),hash,sqlTime(BASE.minusSeconds(121)),sqlTime(BASE.minusSeconds(1)),sqlTime(BASE));
        long event=h.store.insert("INSERT INTO outbox_event(event_type,aggregate_id,payload,state,created_at,finished_at) VALUES('BOOKING_REQUESTED',?,JSON_OBJECT(),'DONE',?,?)",sentinel,sqlTime(BASE.minusSeconds(121)),sqlTime(BASE));
        h.jdbc.update("UPDATE outbox_event SET payload=? WHERE id=?",h.json.writeValueAsString(Map.of("schemaVersion",1,"eventId",Long.toString(event),"eventType","BOOKING_REQUESTED","requestId",Long.toString(sentinel),"createdAt",time(BASE.minusSeconds(121)),"traceId","fixture-high-id")),event);
        var r=h.post(f,"c16-large-generated");assertEquals(202,r.status());assertTrue(r.id()>9007199254740992L);
        assertTrue(r.body().path("requestId").isTextual());assertEquals(Long.toString(r.id()),h.query(r.id()).body().path("requestId").asText());
        h.assertCounts(f,2,1,1,0);done("C16","strict DTO/duplicate JSON/Long upper bound; real generated request ID above JS safe integer remains a string; one expired boundary seed included",f);
    }
    void c17() throws Exception {
        var f=h.fixture("C17",1);var r=h.post(f,"c17-hold-overdue");assertEquals(202,r.status());
        var before=h.query(r.id()).body().path("order");h.runtime.fixedTime=BASE.plusSeconds(601);scanner().scanOnce();allocation().process(r.id());
        var after=h.query(r.id()).body();assertEquals("SUCCEEDED",after.path("status").asText());assertEquals(before,after.path("order"));
        h.assertCounts(f,1,1,1,0);done("C17","overdue HOLD intentionally remains occupied: no payment, release or fake cancellation",f);
    }
    void c18() throws Exception {
        var f=h.fixture("C18",1);h.runtime.direct=false;var r=h.post(f,"c18-paused-after");
        h.jdbc.update("UPDATE event_session SET state='PAUSED' WHERE id=?",f.session());scanner().scanOnce();
        assertEquals("SESSION_NOT_OPEN",reason(r.id()));h.assertCounts(f,1,0,0,1);
        long history=h.jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history",Long.class);
        var env=MigrationTest.environment();env.setProperty("biz.runId",h.runId);
        env.setActiveProfiles("biz01");assertThrows(IllegalStateException.class,()->Bootstrap.prepare(env));
        env.setActiveProfiles();assertThrows(IllegalStateException.class,()->Bootstrap.prepare(env));
        for(String key:List.of("biz.db.name","biz.db.user","biz.db.migrator","biz.db.port","server.port")){
            var wrong=MigrationTest.environment().withProperty(key,"wrong");assertThrows(IllegalStateException.class,()->Bootstrap.prepare(wrong));
        }
        com.ticketing.booking.fixture.TestRuntime.enabled=false;
        try{assertThrows(IllegalStateException.class,()->Bootstrap.prepare(MigrationTest.environment()));}
        finally{com.ticketing.booking.fixture.TestRuntime.enabled=true;}
        assertEquals(history,h.jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history",Long.class));
        h.save("C18-guards.json",Map.of("migrationHistoryUnchanged",true,"missingIdentityFixtureRejected",true,"profileAndTargetGuardsRejected",true));
        done("C18","pause after acceptance persists rejection; profiles/targets/missing identity refused before database migration",f);
    }
    void c19() throws Exception {
        List<Fixture> fixtures=new ArrayList<>();
        for(String mode:List.of("limit-invisible","visible-recheck","window-invisible")){
            var f=h.fixture("C19-"+mode,1);fixtures.add(f);
            CountDownLatch firstHeld=new CountDownLatch(1),releaseFirst=new CountDownLatch(1);
            String point=mode.equals("window-invisible")?"T1_BEFORE_COMMIT":"BEFORE_T1";
            h.runtime.hooks.put(point,id->{firstHeld.countDown();waitLatch(releaseFirst);});
            ExecutorService executor=Executors.newFixedThreadPool(2);
            try{
                Future<Reply> first=executor.submit(()->h.post(f,"c19-shared-key"));waitLatch(firstHeld);
                if(mode.equals("limit-invisible")){
                    var redis=h.app.getBean(StringRedisTemplate.class);var keys=h.app.getBean(EntryLimiter.class).keys(f.userA(),f.tier(),BASE);
                    h.runtime.observedRedisKeys.addAll(keys);redis.opsForValue().set(keys.get(0),"5",Duration.ofSeconds(10));
                    assertEquals(429,h.post(f,"c19-shared-key").status());releaseFirst.countDown();assertEquals(202,first.get(15,TimeUnit.SECONDS).status());
                }else if(mode.equals("window-invisible")){
                    h.runtime.fixedTime=BASE.plusSeconds(3600);assertEquals(409,h.post(f,"c19-shared-key").status());
                    releaseFirst.countDown();assertEquals(202,first.get(15,TimeUnit.SECONDS).status());
                }else{
                    CountDownLatch secondReached=new CountDownLatch(1),releaseSecond=new CountDownLatch(1);
                    h.runtime.hooks.put("BEFORE_REDIS",id->{secondReached.countDown();waitLatch(releaseSecond);throw new IllegalStateException("INJECTED_CLIENT_REJECTION");});
                    Future<Reply> second=executor.submit(()->h.post(f,"c19-shared-key"));waitLatch(secondReached);releaseFirst.countDown();
                    Reply committed=first.get(15,TimeUnit.SECONDS);assertEquals(202,committed.status());releaseSecond.countDown();
                    assertEquals(committed.body(),second.get(15,TimeUnit.SECONDS).body());
                }
            }finally{releaseFirst.countDown();h.runtime.hooks.clear();executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
            var retry=h.post(f,"c19-shared-key");assertEquals(202,retry.status());
            if(mode.equals("window-invisible")){assertEquals("EXPIRED",state(retry.id()));h.assertCounts(f,1,0,0,1);}
            else{assertEquals("SUCCEEDED",state(retry.id()));h.assertCounts(f,1,1,1,0);}
        }
        done("C19","barrier-controlled first-request visibility against limiter, dependency rejection recheck and closing window; one durable identity",fixtures.toArray(Fixture[]::new));
    }
}
