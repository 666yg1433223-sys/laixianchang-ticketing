package com.ticketing.booking.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.booking.application.BookingApplication;
import com.ticketing.booking.config.*;
import com.ticketing.booking.fixture.*;
import com.ticketing.booking.fixture.BookingHarness.*;
import com.ticketing.booking.recovery.*;
import com.ticketing.booking.service.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import static com.ticketing.booking.domain.Values.*;
import static com.ticketing.booking.fixture.BookingHarness.BASE;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP management tests. Every writable target is created and enrolled by this fixture. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentManagementIntegrationTest {
    static final String ROOT="/v1/internal/sim";
    static final int CASES=17;
    BookingHarness h;
    OrderContractTest contract;
    record Payment(Fixture fixture,long request,long order,long attempt) { }
    interface Scenario { void run() throws Exception; }
    @BeforeAll void open() throws Exception {
        h=new BookingHarness(System.getProperty("biz.runId")+"-management",true);
        contract=new OrderContractTest();contract.spec=h.json.readTree(Files.readString(Bootstrap.ROOT.resolve("docs/biz04-openapi.json")));
    }
    @AfterAll void close() throws Exception { if(h!=null){h.cleanOnClose=h.passed.size()==CASES;h.close();} }
    @TestFactory Stream<DynamicTest> scenarios() {
        List<Scenario> scenarios=List.of(this::m01,this::m02,this::m03,this::m04,this::m05,this::m06,this::m07,
            this::m08,this::m09,this::m10,this::m11,this::m12,this::m13,this::m14,this::m15,this::m16,this::m17);
        return IntStream.range(0,scenarios.size()).mapToObj(i->DynamicTest.dynamicTest(String.format("M%02d",i+1),()->scenarios.get(i).run()));
    }
    PaymentService payment() { return h.app.getBean(PaymentService.class); }
    Payment pending(String name,String result) throws Exception {
        return pending(name,result,true);
    }
    Payment pending(String name,String result,boolean enroll) throws Exception {
        var f=h.fixture(name,1);h.runtime.plan=new RuntimeServices.SimulationPlan(result,100);
        var request=h.post(f,name+"-booking-key");assertEquals(202,request.status(),request.body().toString());
        long order=Long.parseLong(h.query(request.id()).body().path("order").path("orderId").asText());
        var attempt=h.request("POST","/v1/orders/"+order+"/payment-attempts","{}",name+"-payment-key",false,true);
        assertEquals(202,attempt.status(),attempt.body().toString());if(enroll)h.refreshManagementAttempts(f);
        return new Payment(f,request.id(),order,Long.parseLong(attempt.body().path("attemptId").asText()));
    }
    Payment ready(String name,String result) throws Exception {
        var p=pending(name,result);h.runtime.fixedTime=BASE.plusSeconds(1);payment().generate(p.attempt());return p;
    }
    Map<String,Object> attempt(Payment p) { return h.jdbc.queryForMap("SELECT * FROM payment_attempt WHERE id=?",p.attempt()); }
    Map<String,Object> event(Payment p) { return h.jdbc.queryForMap("SELECT * FROM outbox_event WHERE payment_attempt_id=? AND event_type='PAYMENT_RESULT_RECORDED'",p.attempt()); }
    long generation(Payment p) { return number(event(p),"recovery_generation"); }
    String resultId(Payment p) { return (String)attempt(p).get("result_id"); }
    String state(Payment p) { return h.jdbc.queryForObject("SELECT status FROM booking_order WHERE id=?",String.class,p.order()); }
    void failSix(Payment p) {
        h.runtime.hooks.put("P2_AFTER_SETTLEMENT",id->{if(id==p.attempt())throw new IllegalStateException("MANAGEMENT_TEST_FAILURE");});
        try {
            for(int i=1;i<=6;i++) {
                h.runtime.fixedTime=instant(event(p).get("next_attempt_at"));payment().settle(p.attempt());
                assertEquals(i,number(event(p),"attempts"));
            }
        } finally { h.runtime.hooks.remove("P2_AFTER_SETTLEMENT"); }
        assertEquals("DEAD",event(p).get("state"));assertEquals("REVIEW",attempt(p).get("settlement_status"));
    }
    Reply recover(Payment p,String key,String reason,String identity) throws Exception {
        var reply=h.managementRequest("POST",ROOT+"/payment-attempts/"+p.attempt()+"/recover",h.json.writeValueAsString(Map.of("reason",reason)),key,identity);
        contract.response(ROOT+"/payment-attempts/{attemptId}/recover","post",reply);return reply;
    }
    Reply recover(Payment p,String key) throws Exception { return recover(p,key,"Investigated transient test failure","operatorA"); }
    Reply redeliver(Payment p,String key,int deliveries) throws Exception {
        var reply=h.managementRequest("POST",ROOT+"/payment-results/"+resultId(p)+"/redeliver",h.json.writeValueAsString(Map.of("reason","Replay stored result","deliveries",deliveries)),key,"operatorA");
        contract.response(ROOT+"/payment-results/{resultId}/redeliver","post",reply);return reply;
    }
    Reply operation(Reply receipt) throws Exception {
        var reply=h.managementRequest("GET",receipt.body().path("statusUrl").asText(),null,null,"operatorA");
        contract.response(ROOT+"/operations/{operationId}","get",reply);return reply;
    }
    void completed(Payment p,Reply receipt,String expected) throws Exception {
        var query=operation(receipt);assertEquals(200,query.status());assertEquals("COMPLETED",query.body().path("status").asText());
        assertEquals(expected,query.body().path("result").asText());assertFalse(query.body().path("completedAt").isNull());
        assertEquals(expected,attempt(p).get("settlement_status"));assertEquals("DONE",event(p).get("state"));
        var own=h.request("GET","/v1/payment-attempts/"+p.attempt(),null,null,false,true);
        assertEquals(200,own.status());assertEquals(expected,own.body().path("settlementStatus").asText());
    }
    void done(String name,String notes,Payment... ps) throws Exception {
        h.runtime.hooks.clear();h.runtime.recovery=false;
        for(var p:ps) h.save(p.fixture().name()+"-facts.json",h.snapshot(p.fixture()));
        h.passed.add(name);h.log(Map.of("case",name,"result","passed","notes",notes));
    }
    static void latch(CountDownLatch gate) { BookingRegressionTest.waitLatch(gate); }
    <T> List<T> parallel(List<Callable<T>> calls) throws Exception {
        var executor=Executors.newFixedThreadPool(calls.size());
        try { var futures=new ArrayList<Future<T>>();for(var call:calls) futures.add(executor.submit(call));
            var results=new ArrayList<T>();for(var future:futures) results.add(future.get(15,TimeUnit.SECONDS));return results;
        } finally {executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
    }
    void m01() throws Exception {
        var p=ready("M01","SUCCESS");var immutable=h.json.valueToTree(attempt(p));failSix(p);
        var failures=h.managementRequest("GET",ROOT+"/payment-failures",null,null,"operatorA");
        assertEquals(200,failures.status());contract.response(ROOT+"/payment-failures","get",failures);
        assertTrue(StreamSupport.stream(failures.body().path("items").spliterator(),false).anyMatch(row->row.path("attemptId").asText().equals(Long.toString(p.attempt()))));
        var receipt=recover(p,"m01-recovery-key");assertEquals(202,receipt.status());assertEquals("ACCEPTED",receipt.body().path("status").asText());
        assertEquals("REVIEW",receipt.body().path("beforeSettlement").asText());assertEquals("DEAD",receipt.body().path("beforeEvent").asText());
        assertEquals(6,receipt.body().path("beforeAttempts").asInt());assertEquals("RETRY_EXHAUSTED",receipt.body().path("beforeError").asText());
        assertEquals("1",receipt.body().path("generation").asText());assertEquals("UNRESOLVED",attempt(p).get("settlement_status"));
        assertEquals("READY",event(p).get("state"));assertEquals(0,number(event(p),"attempts"));assertEquals("ACCEPTED",operation(receipt).body().path("status").asText());
        h.app.getBean(PaymentRecoveryScanner.class).settleOnce();completed(p,receipt,"APPLIED");
        for(String field:List.of("result_id","result_digest","result_status","amount_cent","currency","result_at","resolution_deadline"))
            assertEquals(immutable.path(field),h.json.valueToTree(attempt(p).get(field)));
        h.assertCounts(p.fixture(),1,1,1,0);done("M01","HTTP failure list, durable 202, original P2 scanner and operation/owner queries converge with immutable result",p);
    }
    void m02() throws Exception {
        var p=ready("M02","SUCCESS");failSix(p);var before=h.json.valueToTree(h.snapshot(p.fixture()));
        String route=ROOT+"/payment-attempts/"+p.attempt()+"/recover";
        assertEquals(401,h.managementRequest("POST",ROOT+"/payment-attempts/invalid/recover","bad",null,null).status());
        for(String identity:List.of("roleOnly","capabilityOnly"))
            assertEquals(403,h.managementRequest("POST",ROOT+"/payment-attempts/invalid/recover","bad",null,identity).status());
        assertEquals(403,h.request("POST",route,"{\"reason\":\"normal user\"}","m02-user-key",false,true).status());
        var forged=java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:18083"+route))
            .header("Authorization","Bearer "+h.runtime.token(false)).header("role","ADMIN").header("userId","10001")
            .header("Idempotency-Key","m02-forged-key").header("Content-Type","application/json")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"reason\":\"forged\"}")).build();
        assertEquals(403,h.http.send(forged,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
        for(String body:List.of("","null","[]","{}","{\"reason\":\" \"}","{\"reason\":\"a\\nb\"}","{\"reason\":\"a\",\"reason\":\"b\"}",
            "{\"reason\":\"a\"} {}","{\"reason\":\"a\",\"amount\":1}","{\"reason\":\"a\",\"outcome\":\"SUCCESS\"}","{\"reason\":\"a\",\"runId\":\"old-run\"}",
            "{\"reason\":\""+"x".repeat(201)+"\"}"))
            assertEquals(400,h.managementRequest("POST",route,body,"m02-invalid-key","operatorA").status(),body);
        for(String key:List.of("","short","has spaces 123","x".repeat(65)))
            assertEquals(400,h.managementRequest("POST",route,"{\"reason\":\"valid\"}",key,"operatorA").status());
        assertEquals(400,h.managementRequest("POST",route+"?userId=1","{\"reason\":\"valid\"}","m02-query-key","operatorA").status());
        for(String query:List.of("?limit=0","?limit=51","?limit=1&limit=2","?runId=previous","?afterAttemptId=-1"))
            assertEquals(400,h.managementRequest("GET",ROOT+"/payment-failures"+query,null,null,"operatorA").status());
        for(String delivery:List.of("0","4","1.5","\"1\""))
            assertEquals(400,h.managementRequest("POST",ROOT+"/payment-results/"+resultId(p)+"/redeliver","{\"reason\":\"valid\",\"deliveries\":"+delivery+"}","m02-delivery-key","operatorA").status());
        assertEquals(before,h.json.valueToTree(h.snapshot(p.fixture())));
        h.stopApp();
        try {
            h.app=new SpringApplication(BookingApplication.class).run("--spring.profiles.active=biz01-it","--biz.runId="+h.runId,"--biz.simulation.enabled=true","--biz.sim.management.enabled=false");
            assertEquals(403,h.managementRequest("GET",ROOT+"/payment-failures",null,null,"operatorA").status());
        } finally {h.stopApp();h.startApp();}
        assertEquals(before,h.json.valueToTree(h.snapshot(p.fixture())));done("M02","Identity-first gate, role/capability separation, forged identity and strict management input rejection without writes",p);
    }
    void m03() throws Exception {
        var p=ready("M03","SUCCESS");failSix(p);
        var unlisted=pending("M03-unlisted","SUCCESS",false);h.runtime.fixedTime=BASE.plusSeconds(1);payment().generate(unlisted.attempt());failSix(unlisted);
        // This attempt belongs to the run but the persisted server manifest deliberately predates its creation.
        var recorded=h.runtime.managementAttempts();assertTrue(recorded.contains(p.attempt()));
        assertFalse(recorded.contains(unlisted.attempt()));
        h.runtime.disableManagement();h.runtime.configureManagement(h.runId,h.jdbc);
        assertEquals(recorded,h.runtime.managementAttempts());
        assertEquals(404,recover(unlisted,"m03-outside-key").status());
        assertEquals(404,redeliver(unlisted,"m03-outside-result-key",1).status());
        assertEquals(404,h.managementRequest("POST",ROOT+"/payment-attempts/"+Long.MAX_VALUE+"/recover","{\"reason\":\"missing\"}","m03-missing-key","operatorA").status());
        String missing=UUID.randomUUID().toString();
        assertEquals(404,h.managementRequest("POST",ROOT+"/payment-results/"+missing+"/redeliver","{\"reason\":\"missing\",\"deliveries\":1}","m03-result-key","operatorA").status());
        assertEquals(404,h.managementRequest("GET",ROOT+"/operations/"+missing,null,null,"operatorA").status());
        var first=h.managementRequest("GET",ROOT+"/payment-failures?limit=1",null,null,"operatorA");assertEquals(200,first.status());assertEquals(1,first.body().path("items").size());
        var page=h.managementRequest("GET",ROOT+"/payment-failures?afterAttemptId="+p.attempt()+"&limit=50",null,null,"operatorA");assertEquals(200,page.status());
        for(var item:page.body().path("items")){assertTrue(Long.parseLong(item.path("attemptId").asText())>p.attempt());assertTrue(recorded.contains(Long.parseLong(item.path("attemptId").asText())));}
        done("M03","Scoped missing target/result/operation responses, bounded ascending pagination and persisted manifest reload",p,unlisted);
    }
    void m04() throws Exception {
        var p=ready("M04","SUCCESS");failSix(p);
        List<Reply> replies=parallel(List.of(()->recover(p,"m04-shared-key","  reviewed  ","operatorA"),()->recover(p,"m04-shared-key","reviewed","operatorA"),()->recover(p,"m04-shared-key","reviewed","operatorA")));
        for(var reply:replies){assertEquals(202,reply.status());assertEquals(replies.get(0).body(),reply.body());}
        assertEquals(1,generation(p));assertEquals(1,h.jdbc.queryForObject("SELECT COUNT(*) FROM sim_payment_operation WHERE attempt_id=?",Integer.class,p.attempt()));
        assertEquals("IDEMPOTENCY_CONFLICT",recover(p,"m04-shared-key","different","operatorA").body().path("code").asText());
        assertEquals("IDEMPOTENCY_CONFLICT",redeliver(p,"m04-shared-key",1).body().path("code").asText());
        payment().settle(p.attempt());var replay=recover(p,"m04-shared-key","reviewed","operatorA");assertEquals(200,replay.status());completed(p,replay,"APPLIED");
        var rejected=recover(p,"m04-new-final-key");assertEquals(409,rejected.status());assertEquals("REJECTED",rejected.body().path("status").asText());
        assertEquals("NOT_RECOVERABLE",rejected.body().path("rejectionReason").asText());assertEquals(1,generation(p));h.assertCounts(p.fixture(),1,1,1,0);
        done("M04","Concurrent normalized same-key acceptance, cross-action/reason conflict and immutable terminal state",p);
    }
    void m05() throws Exception {
        var p=ready("M05","SUCCESS");failSix(p);
        List<Reply> results=parallel(List.of(()->recover(p,"m05-operator-a","operator A investigation","operatorA"),()->recover(p,"m05-operator-b","operator B investigation","operatorB")));
        assertEquals(List.of(202,409),results.stream().map(Reply::status).sorted().toList());assertEquals(1,generation(p));
        var accepted=results.stream().filter(r->r.status()==202).findFirst().orElseThrow();var rejected=results.stream().filter(r->r.status()==409).findFirst().orElseThrow();
        assertNotEquals(accepted.body().path("operatorId"),rejected.body().path("operatorId"));assertEquals("REJECTED",rejected.body().path("status").asText());
        assertTrue(rejected.body().path("acceptedAt").isNull());assertFalse(rejected.body().path("completedAt").isNull());
        assertEquals(2,h.jdbc.queryForObject("SELECT COUNT(*) FROM sim_payment_operation WHERE attempt_id=?",Integer.class,p.attempt()));
        payment().settle(p.attempt());completed(p,accepted,"APPLIED");done("M05","Two trusted operators compete for one recovery generation; losing request is durably rejected",p);
    }
    void m06() throws Exception {
        var ready=ready("M06-ready","SUCCESS");var first=redeliver(ready,"m06-ready-key",3);assertTrue(Set.of(200,202).contains(first.status()));
        payment().settle(ready.attempt());completed(ready,first,"APPLIED");var facts=h.json.valueToTree(attempt(ready));
        var replay=redeliver(ready,"m06-final-key",3);assertEquals(200,replay.status());completed(ready,replay,"APPLIED");assertEquals(facts,h.json.valueToTree(attempt(ready)));assertEquals(0,generation(ready));
        var dead=ready("M06-dead","SUCCESS");failSix(dead);var denied=redeliver(dead,"m06-dead-key",1);
        assertEquals(409,denied.status());assertEquals("RECOVERY_REQUIRED",denied.body().path("rejectionReason").asText());assertEquals("DEAD",event(dead).get("state"));assertEquals(0,generation(dead));
        var backoff=ready("M06-backoff","SUCCESS");
        h.runtime.hooks.put("P2_AFTER_SETTLEMENT",id->{if(id==backoff.attempt())throw new IllegalStateException("M06_BACKOFF");});payment().settle(backoff.attempt());h.runtime.hooks.clear();
        var before=h.json.valueToTree(event(backoff));var queued=redeliver(backoff,"m06-backoff-key",3);assertEquals(202,queued.status());assertEquals(before,h.json.valueToTree(event(backoff)));
        h.runtime.fixedTime=instant(event(backoff).get("next_attempt_at"));payment().settle(backoff.attempt());completed(backoff,queued,"APPLIED");
        done("M06","READY/DONE/DEAD redelivery uses stored results, respects due time and never creates a recovery generation",ready,dead,backoff);
    }
    void m07() throws Exception {
        var late=ready("M07-late","SUCCESS");failSix(late);h.runtime.fixedTime=BASE.plusSeconds(600);
        h.app.getBean(OrderExpiryScanner.class).scanOnce();assertEquals("EXPIRED",state(late));var accepted=recover(late,"m07-late-key");assertEquals(202,accepted.status());payment().settle(late.attempt());completed(late,accepted,"REVERSED");
        var compensation=attempt(late).get("compensation_id");assertNotNull(compensation);redeliver(late,"m07-late-replay",3);assertEquals(compensation,attempt(late).get("compensation_id"));h.assertCounts(late.fixture(),1,1,2,1);
        var failed=ready("M07-failed","FAILED");failSix(failed);var failedAccepted=recover(failed,"m07-failed-key");payment().settle(failed.attempt());completed(failed,failedAccepted,"NO_CHARGE");assertEquals("HOLD",state(failed));
        assertEquals(409,recover(failed,"m07-failed-final").status());h.assertCounts(failed.fixture(),1,1,1,0);
        var old=ready("M07-owner","SUCCESS");failSix(old);
        assertEquals(200,h.request("POST","/v1/orders/"+old.order()+"/cancel","{}",null,false,true).status());
        String input=h.input(old.fixture()).replace("\"tierId\":\""+old.fixture().tier()+"\"","\"tierId\":\""+old.fixture().otherTier()+"\"");
        var next=h.request("POST","/v1/booking-requests",input,"m07-new-owner-key",false,true);assertEquals(202,next.status());
        long owner=Long.parseLong(h.query(next.id()).body().path("order").path("orderId").asText());
        var quota=h.json.valueToTree(h.jdbc.queryForMap("SELECT * FROM user_session_quota WHERE user_id=? AND session_id=?",old.fixture().userA(),old.fixture().session()));
        var oldAccepted=recover(old,"m07-old-recovery");payment().settle(old.attempt());completed(old,oldAccepted,"REVERSED");redeliver(old,"m07-old-redelivery",3);
        assertEquals(quota,h.json.valueToTree(h.jdbc.queryForMap("SELECT * FROM user_session_quota WHERE user_id=? AND session_id=?",old.fixture().userA(),old.fixture().session())));
        assertEquals("HOLD",h.jdbc.queryForObject("SELECT status FROM booking_order WHERE id=?",String.class,owner));h.assertCounts(old.fixture(),2,2,3,1);
        done("M07","Late recovery reverses once, failed recovery is NO_CHARGE, old closed order recovery preserves new cross-tier owner",late,failed,old);
    }
    void m08() throws Exception {
        var p=ready("M08","SUCCESS");byte[] digest=(byte[])attempt(p).get("result_digest");
        h.jdbc.update("UPDATE payment_attempt SET result_digest=UNHEX(SHA2('fixture-corruption',256)) WHERE id=?",p.attempt());payment().settle(p.attempt());
        assertEquals("REVIEW",attempt(p).get("settlement_status"));var before=h.json.valueToTree(event(p));
        var rejected=recover(p,"m08-reject-key");assertEquals(409,rejected.status());assertEquals("TRUSTED_RESULT_INCONSISTENT",rejected.body().path("rejectionReason").asText());
        assertEquals(before,h.json.valueToTree(event(p)));assertEquals(0,generation(p));assertEquals("HOLD",state(p));
        h.jdbc.update("UPDATE payment_attempt SET result_digest=? WHERE id=?",digest,p.attempt());
        var accepted=recover(p,"m08-restored-key");assertEquals(202,accepted.status());payment().settle(p.attempt());completed(p,accepted,"APPLIED");h.assertCounts(p.fixture(),1,1,1,0);
        done("M08","Corrupted trusted digest is auditable rejection; only fixture restoration of original bytes allows later recovery",p);
    }
    void m09() throws Exception {
        var observed=ready("M09-observed","SUCCESS");var reached=new CountDownLatch(1);var release=new CountDownLatch(1);var oldThread=new AtomicReference<Thread>();
        h.runtime.hooks.put("P2_OBSERVED",id->{if(id==observed.attempt() && Thread.currentThread()==oldThread.get()){reached.countDown();latch(release);}});
        var executor=Executors.newSingleThreadExecutor();
        try {
            var old=executor.submit(()->{oldThread.set(Thread.currentThread());payment().settle(observed.attempt());});latch(reached);failSix(observed);
            var accepted=recover(observed,"m09-observed-key");assertEquals(202,accepted.status());release.countDown();old.get(12,TimeUnit.SECONDS);
            assertEquals("UNRESOLVED",attempt(observed).get("settlement_status"));assertEquals("ACCEPTED",operation(accepted).body().path("status").asText());assertEquals(0,number(event(observed),"attempts"));
            payment().settle(observed.attempt());completed(observed,accepted,"APPLIED");
        } finally {release.countDown();h.runtime.hooks.clear();executor.shutdownNow();}
        var count=ready("M09-count","SUCCESS");var counting=new CountDownLatch(1);var resume=new CountDownLatch(1);var staleThread=new AtomicReference<Thread>();
        h.runtime.hooks.put("P2_AFTER_SETTLEMENT",id->{if(id==count.attempt())throw new IllegalStateException("M09_OLD_FAILURE");});
        h.runtime.hooks.put("P2_BEFORE_FAILURE_RECORD",id->{if(Thread.currentThread()==staleThread.get()){counting.countDown();latch(resume);}});
        executor=Executors.newSingleThreadExecutor();
        try {
            var old=executor.submit(()->{staleThread.set(Thread.currentThread());payment().settle(count.attempt());});latch(counting);failSix(count);
            var accepted=recover(count,"m09-count-key");assertEquals(202,accepted.status());resume.countDown();old.get(12,TimeUnit.SECONDS);
            assertEquals(1,generation(count));assertEquals(0,number(event(count),"attempts"));assertEquals("ACCEPTED",operation(accepted).body().path("status").asText());
            h.runtime.hooks.clear();payment().settle(count.attempt());completed(count,accepted,"APPLIED");
        } finally {resume.countDown();h.runtime.hooks.clear();executor.shutdownNow();}
        done("M09","Paused old P2 observation and paused old failure recorder cannot act on a newly accepted generation",observed,count);
    }
    void m10() throws Exception {
        var p=ready("M10","SUCCESS");failSix(p);var first=recover(p,"m10-first-round");assertEquals(202,first.status());failSix(p);
        var completed=operation(first);assertEquals("COMPLETED",completed.body().path("status").asText());assertEquals("REVIEW",completed.body().path("result").asText());
        var before=h.json.valueToTree(event(p));for(int i=0;i<3;i++)payment().settle(p.attempt());assertEquals(before,h.json.valueToTree(event(p)));assertEquals(1,generation(p));
        var second=recover(p,"m10-second-round");assertEquals(202,second.status());assertEquals("2",second.body().path("generation").asText());
        payment().settle(p.attempt());completed(p,second,"APPLIED");assertEquals(completed.body(),operation(first).body());h.assertCounts(p.fixture(),1,1,1,0);
        done("M10","Each manually accepted generation gets at most six automatic failures and completes its own immutable audit",p);
    }
    void m11() throws Exception {
        var used=new ArrayList<Payment>();
        for(String point:List.of("MGMT_AFTER_AUDIT","MGMT_AFTER_GENERATION","MGMT_AFTER_REOPEN","MGMT_BEFORE_COMMIT")) {
            var p=ready("M11-"+point.toLowerCase(Locale.ROOT).replace('_','-'),"SUCCESS");used.add(p);failSix(p);var before=h.json.valueToTree(h.snapshot(p.fixture()));
            h.runtime.hooks.put(point,id->{throw new IllegalStateException("INJECTED_MANAGEMENT_ROLLBACK");});
            String key="m11-"+point.toLowerCase(Locale.ROOT);
            assertEquals(503,recover(p,key).status());h.runtime.hooks.clear();assertEquals(before,h.json.valueToTree(h.snapshot(p.fixture())));
            var accepted=recover(p,key);assertEquals(202,accepted.status());
            h.runtime.hooks.put("P2_AFTER_AUDIT",id->{throw new IllegalStateException("INJECTED_AUDIT_COMPLETION_ROLLBACK");});
            h.runtime.hooks.put("P2_BEFORE_FAILURE_RECORD",id->{throw new IllegalStateException("PAUSE_FAILURE_COUNT_FOR_ROLLBACK_ASSERTION");});
            var acceptedFacts=h.json.valueToTree(h.snapshot(p.fixture()));payment().settle(p.attempt());h.runtime.hooks.clear();assertEquals(acceptedFacts,h.json.valueToTree(h.snapshot(p.fixture())));
            payment().settle(p.attempt());completed(p,accepted,"APPLIED");
        }
        done("M11","Independent SQL snapshots prove all management writes and P2 audit completion roll back atomically",used.toArray(Payment[]::new));
    }
    void m12() throws Exception {
        var p=ready("M12-ack","SUCCESS");failSix(p);
        h.runtime.hooks.put("MGMT_COMMITTED",id->{throw new IllegalStateException("INJECTED_MANAGEMENT_ACK_LOSS");});
        var lost=recover(p,"m12-ack-key");assertEquals(503,lost.status());assertEquals("OPERATION_ACCEPTANCE_UNKNOWN",lost.body().path("code").asText());h.runtime.hooks.clear();
        var accepted=recover(p,"m12-ack-key");assertEquals(202,accepted.status());assertEquals(1,generation(p));payment().settle(p.attempt());completed(p,accepted,"APPLIED");
        var wire=ready("M12-wire","SUCCESS");failSix(wire);var reached=new CountDownLatch(1);var release=new CountDownLatch(1);
        h.runtime.hooks.put("BEFORE_MANAGEMENT_RESPONSE",id->{reached.countDown();latch(release);});
        try(var socket=new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1",18083),3000);String body="{\"reason\":\"Investigated transient test failure\"}";
            String request="POST "+ROOT+"/payment-attempts/"+wire.attempt()+"/recover HTTP/1.1\r\nHost: 127.0.0.1:18083\r\nAuthorization: Bearer "+h.runtime.operatorToken("operatorA")+"\r\nIdempotency-Key: "+BookingHarness.managementKey(h.runId,"m12-wire-key")+"\r\nContent-Type: application/json\r\nContent-Length: "+body.getBytes(StandardCharsets.UTF_8).length+"\r\nConnection: close\r\n\r\n"+body;
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));socket.getOutputStream().flush();latch(reached);
            assertEquals(1,generation(wire));assertEquals(1,h.jdbc.queryForObject("SELECT COUNT(*) FROM sim_payment_operation WHERE attempt_id=? AND status='ACCEPTED'",Integer.class,wire.attempt()));
            socket.setSoLinger(true,0);socket.close();
        } finally {h.runtime.hooks.clear();release.countDown();}
        var replay=recover(wire,"m12-wire-key");assertEquals(202,replay.status());assertEquals(1,generation(wire));payment().settle(wire.attempt());completed(wire,replay,"APPLIED");
        done("M12","Commit ACK exception and real TCP reset after persistent acceptance both recover through the same key",p,wire);
    }
    void m13() throws Exception {
        var p=ready("M13","SUCCESS");failSix(p);h.stopApp();Process crash=null,recover=null;
        try {
            crash=child(p,"accept-crash");assertTrue(crash.waitFor(12,TimeUnit.SECONDS));assertEquals(71,crash.exitValue());
            assertEquals(1,generation(p));assertEquals("READY",event(p).get("state"));assertEquals("UNRESOLVED",attempt(p).get("settlement_status"));
            assertEquals(1,h.jdbc.queryForObject("SELECT COUNT(*) FROM sim_payment_operation WHERE attempt_id=? AND status='ACCEPTED'",Integer.class,p.attempt()));
            recover=child(p,"recover");assertNotEquals(crash.pid(),recover.pid());BookingHarness.await(()->"PAID".equals(state(p)));
            var replay=recover(p,"m13-process-key");assertEquals(200,replay.status());completed(p,replay,"APPLIED");
            h.save("M13-process-result.json",Map.of("crashPid",crash.pid(),"recoveryPid",recover.pid(),"crashExit",71,"generation",generation(p),"manifestLoadedFromDisk",true,"databaseRestarted",false));
        } finally {stopChild(recover);stopChild(crash);h.startApp();}
        h.assertCounts(p.fixture(),1,1,1,0);done("M13","Actual application halt after recovery commit; fresh PID loads private run manifest and scanner completes original P2 plus audit",p);
    }
    void m14() throws Exception {
        var p=ready("M14-decision","SUCCESS");failSix(p);var accepted=recover(p,"m14-decision-key");h.runtime.fixedTime=BASE.plusSeconds(599);
        var decided=new CountDownLatch(1);var commit=new CountDownLatch(1);
        h.runtime.hooks.put("P2_BEFORE_COMMIT",id->{if(id==p.attempt()){decided.countDown();latch(commit);}});
        var executor=Executors.newSingleThreadExecutor();
        try {
            var work=executor.submit(()->payment().settle(p.attempt()));latch(decided);
            assertEquals("HOLD",state(p));assertEquals("ACCEPTED",operation(accepted).body().path("status").asText());
            h.runtime.fixedTime=BASE.plusSeconds(601);commit.countDown();work.get(12,TimeUnit.SECONDS);
        } finally {commit.countDown();h.runtime.hooks.clear();executor.shutdownNow();}
        completed(p,accepted,"APPLIED");assertEquals(BASE.plusSeconds(599),instant(attempt(p).get("settled_at")));
        var locked=ready("M14-audit-lock","SUCCESS");failSix(locked);var lockedAccepted=recover(locked,"m14-audit-lock-key");
        h.runtime.fixedTime=BASE.plusSeconds(599);var reads=new AtomicInteger();h.runtime.hooks.put("CLOCK_AFTER_LOCKS",id->reads.incrementAndGet());
        executor=Executors.newSingleThreadExecutor();
        try(var connection=h.probe.getConnection()) {
            connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement("SELECT operation_id FROM sim_payment_operation WHERE operation_id=? FOR UPDATE")) {
                statement.setString(1,lockedAccepted.body().path("operationId").asText());try(var rows=statement.executeQuery()){assertTrue(rows.next());}
            }
            var work=executor.submit(()->payment().settle(locked.attempt()));
            BookingHarness.await(()->h.jdbc.queryForList("SHOW FULL PROCESSLIST").stream().anyMatch(row->row.get("Info")!=null && row.get("Info").toString().contains("sim_payment_operation") && row.get("Info").toString().contains("FOR UPDATE")));
            assertEquals(0,reads.get());h.runtime.fixedTime=BASE.plusSeconds(600);connection.rollback();work.get(12,TimeUnit.SECONDS);
        } finally {h.runtime.hooks.clear();executor.shutdownNow();}
        completed(locked,lockedAccepted,"REVERSED");h.assertCounts(locked.fixture(),1,1,2,1);
        done("M14","Decision before D remains PAID despite commit after D; real audit-row lock wait obtains decision clock only after lock and reverses at D",p,locked);
    }
    void m15() throws Exception {
        var f=h.fixture("M15-p0",1);var request=h.post(f,"m15-p0-booking-key");assertEquals(202,request.status());
        long order=Long.parseLong(h.query(request.id()).body().path("order").path("orderId").asText());
        var p=new Payment(f,request.id(),order,0);h.stopApp();Process crash=null,recover=null;
        try {
            crash=child(p,"p0-crash");assertTrue(crash.waitFor(12,TimeUnit.SECONDS));assertEquals(71,crash.exitValue());
            var row=h.jdbc.queryForMap("SELECT * FROM payment_attempt WHERE order_id=?",order);
            p=new Payment(f,request.id(),order,number(row,"id"));assertEquals("PENDING",row.get("result_status"));
            assertEquals(0,h.jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE payment_attempt_id=?",Integer.class,p.attempt()));
            h.runtime.configureManagement(h.runId,h.jdbc);assertTrue(h.runtime.managementAttempts().contains(p.attempt()));
            recover=child(p,"p0-recover");assertNotEquals(crash.pid(),recover.pid());Payment recovered=p;
            BookingHarness.await(()->"PAID".equals(state(recovered)));
            for(String field:List.of("id","created_at","planned_status","planned_at","resolution_deadline","amount_cent","currency"))
                assertEquals(h.json.valueToTree(row.get(field)),h.json.valueToTree(attempt(p).get(field)));
            assertEquals(1,h.jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempt WHERE order_id=?",Integer.class,order));
            var repeated=h.request("POST","/v1/orders/"+order+"/payment-attempts","{}","m15-p0-payment-key",false,true);
            assertEquals(202,repeated.status());assertEquals(Long.toString(p.attempt()),repeated.body().path("attemptId").asText());
            h.save("M15-p0-restart.json",Map.of("crashPid",crash.pid(),"recoveryPid",recover.pid(),"crashExit",71,"attemptId",Long.toString(p.attempt()),"originalPlanUnchanged",true));
        } finally {stopChild(recover);stopChild(crash);h.startApp();}
        h.assertCounts(f,1,1,1,0);
        var paused=ready("M15-paused","SUCCESS");
        h.jdbc.update("UPDATE event_session SET state='PAUSED' WHERE id=?",paused.fixture().session());
        var denied=h.request("POST","/v1/booking-requests",h.input(paused.fixture()),"m15-paused-new-key",true,true);
        assertEquals(409,denied.status());assertEquals("SESSION_NOT_OPEN",denied.body().path("code").asText());
        payment().settle(paused.attempt());assertEquals("APPLIED",attempt(paused).get("settlement_status"));assertEquals("PAID",state(paused));
        assertEquals(1,h.jdbc.queryForObject("SELECT COUNT(*) FROM booking_order WHERE session_id=?",Integer.class,paused.fixture().session()));
        done("M15","Actual P0 commit halt preserves original attempt and plan across new PID; pausing a session blocks new allocation while an existing HOLD can pay",p,paused);
    }
    void m16() throws Exception {
        var used=new ArrayList<Payment>();
        for(boolean expiry:List.of(false,true)) {
            for(boolean payFirst:List.of(false,true)) {
                var p=ready("M16-"+(expiry?"expiry-":"cancel-")+(payFirst?"payment":"close"),"SUCCESS");
                used.add(p);failSix(p);var accepted=recover(p,p.fixture().name()+"-recovery");
                h.runtime.fixedTime=BASE.plusSeconds(expiry && !payFirst?600:599);
                var winnerLocked=new CountDownLatch(1);var release=new CountDownLatch(1);
                String boundary=payFirst?"P2_BEFORE_COMMIT":"CLOSE_LOCKS_ACQUIRED";
                h.runtime.hooks.put(boundary,id->{winnerLocked.countDown();latch(release);});
                var executor=Executors.newFixedThreadPool(2);
                Callable<Integer> settle=()->{payment().settle(p.attempt());return 0;};
                Callable<Integer> close=()->{
                    if(expiry) {h.app.getBean(OrderCloseService.class).expire(p.order());return 200;}
                    return h.request("POST","/v1/orders/"+p.order()+"/cancel","{}",null,false,true).status();
                };
                try {
                    var winner=executor.submit(payFirst?settle:close);latch(winnerLocked);
                    if(expiry && payFirst) {h.runtime.fixedTime=BASE.plusSeconds(601);}
                    var loser=executor.submit(payFirst?close:settle);
                    // Observe the actual SQL waiting for the winner's inventory row, not just a Java thread starting.
                    BookingHarness.await(()->h.jdbc.queryForList("SHOW FULL PROCESSLIST").stream().anyMatch(row->{
                        String sql=Objects.toString(row.get("Info"),"");
                        return sql.startsWith("SELECT * FROM ticket_tier") && sql.contains("FOR UPDATE");
                    }));
                    assertEquals("HOLD",state(p));assertEquals("ACCEPTED",operation(accepted).body().path("status").asText());
                    h.save(p.fixture().name()+"-overlap.json",Map.of("actualInventoryLockWait",true,"expiry",expiry,"paymentWon",payFirst));
                    release.countDown();int won=winner.get(12,TimeUnit.SECONDS),lost=loser.get(12,TimeUnit.SECONDS);
                    assertEquals(payFirst && !expiry?409:200,payFirst?lost:won);
                } finally {release.countDown();h.runtime.hooks.clear();executor.shutdownNow();}
                completed(p,accepted,payFirst?"APPLIED":"REVERSED");
                h.assertCounts(p.fixture(),1,1,payFirst?1:2,payFirst?0:1);
            }
        }
        done("M16","Recovered P2 competes with cancellation and expiry under actual SQL lock waits, both winners preserve owner and release invariants",used.toArray(Payment[]::new));
    }
    void m17() throws Exception {
        var failed=ready("M17","FAILED");payment().settle(failed.attempt());assertEquals("NO_CHARGE",attempt(failed).get("settlement_status"));
        h.runtime.plan=new RuntimeServices.SimulationPlan("SUCCESS",100);
        var submitted=h.request("POST","/v1/orders/"+failed.order()+"/payment-attempts","{}","m17-new-success-key",false,true);assertEquals(202,submitted.status());
        var success=new Payment(failed.fixture(),failed.request(),failed.order(),Long.parseLong(submitted.body().path("attemptId").asText()));
        h.refreshManagementAttempts(failed.fixture());h.runtime.fixedTime=BASE.plusSeconds(2);payment().generate(success.attempt());
        var oldBefore=h.json.valueToTree(attempt(failed));var old=redeliver(failed,"m17-old-first-key",3);assertEquals(200,old.status());completed(failed,old,"NO_CHARGE");
        assertEquals("HOLD",state(success));var current=redeliver(success,"m17-new-result-key",3);payment().settle(success.attempt());completed(success,current,"APPLIED");
        var paidBefore=h.json.valueToTree(attempt(success));var last=redeliver(failed,"m17-old-last-key",3);assertEquals(200,last.status());
        assertEquals(oldBefore,h.json.valueToTree(attempt(failed)));assertEquals(paidBefore,h.json.valueToTree(attempt(success)));
        assertEquals("PAID",state(success));assertEquals(success.attempt(),h.jdbc.queryForObject("SELECT paid_attempt_id FROM booking_order WHERE id=?",Long.class,success.order()));
        assertEquals(0,generation(failed));assertEquals(0,generation(success));h.assertCounts(failed.fixture(),1,1,1,0);
        done("M17","Old FAILED result redelivery before and after newer SUCCESS preserves paid-attempt linkage and both authoritative result rows",failed,success);
    }
    Process child(Payment p,String mode) throws Exception {
        String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        String prefix=mode.startsWith("p0-")?"M15":"M13";
        String options="-Xmx256m\n-Dfile.encoding=UTF-8\n-Dbiz.evidenceTask=BIZ-04-I-001\n-cp\n\""+cp.replace('\\','/')+"\"\n"+PaymentManagementIntegrationTest.class.getName()+"\n"+h.runId+"\n"+mode+"\n"+p.fixture().userA()+"\n"+p.fixture().userB()+"\n"+(mode.equals("p0-crash")?p.order():p.attempt())+"\n"+p.fixture().name()+"\n";
        Path arguments=h.evidence.resolve(prefix+"-"+mode+"-args.txt");Files.writeString(arguments,options);
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"@"+arguments)
            .directory(Bootstrap.ROOT.toFile()).redirectErrorStream(true).redirectOutput(h.evidence.resolve(prefix+"-"+mode+"-process.log").toFile()).start();
        try {BookingHarness.await(()->Files.exists(h.evidence.resolve(prefix+"-"+mode+"-ready.json")) || !process.isAlive());
            assertTrue(Files.exists(h.evidence.resolve(prefix+"-"+mode+"-ready.json")),"Child failed startup");}
        catch(Throwable failure){if(process.isAlive())process.destroyForcibly().waitFor(5,TimeUnit.SECONDS);throw failure;}return process;
    }
    static void stopChild(Process child) throws Exception {
        if(child!=null && child.isAlive()) {
            child.getOutputStream().write("STOP\n".getBytes(StandardCharsets.UTF_8));child.getOutputStream().flush();
            if(!child.waitFor(8,TimeUnit.SECONDS)){child.destroyForcibly().waitFor(5,TimeUnit.SECONDS);fail("Own child failed graceful stop");}
            assertEquals(0,child.exitValue());
        }
    }
    public static void main(String[] args) throws Exception {
        String run=args[0],mode=args[1];long id=Long.parseLong(args[4]);
        if(!run.matches("[a-z0-9-]{8,50}") || !Set.of("accept-crash","recover","p0-crash","p0-recover").contains(mode) || id<=0 || !args[5].matches("[A-Za-z0-9-]{1,70}")) throw new IllegalArgumentException("Invalid child fixture");
        var runtime=TestRuntime.instance();runtime.reset();runtime.userA=Long.parseLong(args[2]);runtime.userB=Long.parseLong(args[3]);
        runtime.fixedTime=mode.equals("p0-crash")?BASE:BASE.plusSeconds(mode.equals("accept-crash")?100:101);runtime.direct=false;runtime.recovery=false;
        var json=new ObjectMapper();var secrets=json.readTree(Files.readString(Bootstrap.ROOT.resolve(".local/credentials.json")));
        try(var probe=Bootstrap.pool("ticketing_biz01_it_app",secrets.path("itApp").asText(),secrets.path("trustPassword").asText(),1)) {
            var jdbc=new JdbcTemplate(probe);runtime.configureManagement(run,jdbc);
            if(mode.equals("p0-crash")) {
                if(jdbc.queryForObject("SELECT COUNT(*) FROM booking_order o JOIN app_user u ON u.id=o.user_id JOIN event_session s ON s.id=o.session_id JOIN event e ON e.id=s.event_id WHERE o.id=? AND o.user_id=? AND e.title=? AND u.subject_key=?",Integer.class,id,runtime.userA,run+":"+args[5],run+":"+args[5]+":a")!=1)
                    throw new IllegalStateException("Child order outside trusted fixture");
            } else if(!runtime.managementAttempts().contains(id)) throw new IllegalStateException("Child target outside fixture manifest");
        }
        Path evidence=Bootstrap.ROOT.resolve("evidence/BIZ-04-I-001").resolve(run);
        if(mode.equals("accept-crash"))runtime.hooks.put("MGMT_COMMITTED",attempt->{
            if(attempt!=id)throw new IllegalStateException("Wrong fixture target");Runtime.getRuntime().halt(71);
        });
        if(mode.equals("p0-crash"))runtime.hooks.put("P0_COMMITTED",attempt->{
            try(var probe=Bootstrap.pool("ticketing_biz01_it_app",secrets.path("itApp").asText(),secrets.path("trustPassword").asText(),1)) {
                runtime.refreshManagementAttempts(new JdbcTemplate(probe));
                if(!runtime.managementAttempts().contains(attempt))throw new IllegalStateException("Unowned P0 attempt");
            } catch(Exception failure) {Runtime.getRuntime().halt(73);}
            Runtime.getRuntime().halt(71);
        });
        CompletableFuture.delayedExecutor(45,TimeUnit.SECONDS).execute(()->Runtime.getRuntime().halt(72));
        try(var app=new SpringApplication(BookingApplication.class).run("--spring.profiles.active=biz01-it","--biz.runId="+run,"--biz.simulation.enabled=true","--biz.sim.management.enabled=true")) {
            String prefix=mode.startsWith("p0-")?"M15":"M13";
            json.writeValue(evidence.resolve(prefix+"-"+mode+"-ready.json").toFile(),Map.of("pid",ProcessHandle.current().pid(),"port",18083));
            if(mode.equals("accept-crash") || mode.equals("p0-crash")) {
                boolean p0=mode.equals("p0-crash");
                var request=java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:18083"+(p0?"/v1/orders/"+id+"/payment-attempts":ROOT+"/payment-attempts/"+id+"/recover")))
                    .header("Authorization","Bearer "+(p0?runtime.token(false):runtime.operatorToken("operatorA"))).header("Idempotency-Key",p0?"m15-p0-payment-key":BookingHarness.managementKey(run,"m13-process-key")).header("Content-Type","application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(p0?"{}":"{\"reason\":\"Investigated transient test failure\"}")).build();
                java.net.http.HttpClient.newHttpClient().send(request,java.net.http.HttpResponse.BodyHandlers.discarding());throw new IllegalStateException("Expected physical process halt");
            }
            // Use the existing scanner with the persisted READY; no direct alternate settlement implementation.
            if(mode.equals("p0-recover"))app.getBean(PaymentRecoveryScanner.class).generateOnce();
            app.getBean(PaymentRecoveryScanner.class).settleOnce();
            if(!"STOP".equals(new BufferedReader(new InputStreamReader(System.in)).readLine()))throw new IllegalStateException("Parent stop missing");
        }
    }
}
