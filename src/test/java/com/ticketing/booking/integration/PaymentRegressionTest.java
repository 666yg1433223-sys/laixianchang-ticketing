package com.ticketing.booking.integration;

import com.ticketing.booking.config.*;
import com.ticketing.booking.fixture.*;
import com.ticketing.booking.fixture.BookingHarness.*;
import com.ticketing.booking.service.*;
import com.ticketing.booking.recovery.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import static com.ticketing.booking.fixture.BookingHarness.BASE;
import static com.ticketing.booking.domain.Values.*;
import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentRegressionTest {
    BookingHarness h; OrderContractTest contract;
    @BeforeAll void open() throws Exception {
        h=new BookingHarness(System.getProperty("biz.runId")+"-payments");
        contract=new OrderContractTest();contract.spec=h.json.readTree(Files.readString(Bootstrap.ROOT.resolve("docs/biz03-openapi.json")));
    }
    @AfterAll void close() throws Exception { if(h!=null) { h.cleanOnClose=h.passed.size()==14;h.close(); } }
    interface Scenario { void run() throws Exception; }
    @TestFactory Stream<DynamicTest> scenarios() {
        List<Scenario> cases=List.of(this::q01,this::q02,this::q03,this::q04,this::q05,this::q06,this::q07,
            this::q08,this::q09,this::q10,this::q11,this::q12,this::q13,this::q14);
        return IntStream.range(0,cases.size()).mapToObj(i->DynamicTest.dynamicTest(String.format("Q%02d",i+1),()->cases.get(i).run()));
    }
    record Hold(long request,long order) { }
    Hold hold(Fixture f,String key) throws Exception {
        var r=h.post(f,key);assertEquals(202,r.status(),r.body().toString());
        var q=h.query(r.id());assertEquals("HOLD",q.body().path("order").path("orderStatus").asText());
        return new Hold(r.id(),Long.parseLong(q.body().path("order").path("orderId").asText()));
    }
    PaymentService payment() { return h.app.getBean(PaymentService.class); }
    Reply pay(long order,String key) throws Exception {
        var r=h.request("POST","/v1/orders/"+order+"/payment-attempts","{}",key,false,true);
        contract.response("/v1/orders/{id}/payment-attempts","post",r);return r;
    }
    long attempt(long order,String key) throws Exception { var r=pay(order,key);assertEquals(202,r.status(),r.body().toString());return Long.parseLong(r.body().path("attemptId").asText()); }
    Reply query(long attempt) throws Exception {
        var r=h.request("GET","/v1/payment-attempts/"+attempt,null,null,false,true);contract.response("/v1/payment-attempts/{id}","get",r);return r;
    }
    Reply cancel(long order) throws Exception { return h.request("POST","/v1/orders/"+order+"/cancel","{}",null,false,true); }
    String state(long order) { return h.jdbc.queryForObject("SELECT status FROM booking_order WHERE id=?",String.class,order); }
    Map<String,Object> row(long id) { return h.jdbc.queryForMap("SELECT * FROM payment_attempt WHERE id=?",id); }
    void result(long id,Instant now) { h.runtime.fixedTime=now;payment().generate(id); }
    void settle(long id,Instant now) { h.runtime.fixedTime=now;payment().settle(id); }
    void assertPayment(long id,String result,String settlement) throws Exception {
        var r=query(id);assertEquals(200,r.status());assertEquals(result,r.body().path("resultStatus").asText());assertEquals(settlement,r.body().path("settlementStatus").asText());
    }
    void done(String code,String notes,List<Fixture> fixtures) throws Exception {
        h.runtime.hooks.clear();h.runtime.recovery=false;
        for(var f:fixtures) { h.save(f.name()+"-facts.json",h.snapshot(f)); }
        h.passed.add(code);h.log(Map.of("case",code,"result","passed","notes",notes));
    }
    void done(String code,String notes,Fixture... fs) throws Exception { done(code,notes,List.of(fs)); }
    static void latch(CountDownLatch gate) { BookingRegressionTest.waitLatch(gate); }
    static void barrier(CyclicBarrier gate) { BookingRegressionTest.barrier(gate); }
    <T> List<T> parallel(List<Callable<T>> calls) throws Exception {
        var executor=Executors.newFixedThreadPool(calls.size());
        try { var futures=new ArrayList<Future<T>>();for(var call:calls) futures.add(executor.submit(call));
            var results=new ArrayList<T>();for(var future:futures)results.add(future.get(20,TimeUnit.SECONDS));return results;
        } finally { h.runtime.hooks.clear();executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS)); }
    }
    void q01() throws Exception {
        var f=h.fixture("Q01",1);var held=hold(f,"q01-booking-key");
        var gate=new CyclicBarrier(4);h.runtime.hooks.put("P0_IDEMPOTENCY_MISS",id->barrier(gate));
        List<Reply> replies=parallel(List.of(()->pay(held.order(),"q01-same-key"),()->pay(held.order(),"q01-same-key"),()->pay(held.order(),"q01-same-key"),()->pay(held.order(),"q01-same-key")));
        for(var r:replies) { assertEquals(202,r.status());assertEquals(replies.get(0).body(),r.body()); }
        long id=Long.parseLong(replies.get(0).body().path("attemptId").asText());
        assertEquals(replies.get(0).body(),pay(held.order(),"q01-same-key").body());
        assertEquals("PAYMENT_IN_PROGRESS",pay(held.order(),"q01-new-key").body().path("code").asText());
        assertEquals(1,h.jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempt WHERE order_id=?",Integer.class,held.order()));
        cancel(held.order());assertEquals(replies.get(0).body(),pay(held.order(),"q01-same-key").body());
        var other=hold(f,"q01-other-order");assertEquals("IDEMPOTENCY_CONFLICT",pay(other.order(),"q01-same-key").body().path("code").asText());
        var reached=new CountDownLatch(1);var release=new CountDownLatch(1);h.runtime.hooks.put("P0_BEFORE_COMMIT",a->{reached.countDown();latch(release);});
        var executor=Executors.newSingleThreadExecutor();
        try {
            var pending=executor.submit(()->pay(other.order(),"q01-invisible-key"));latch(reached);
            assertEquals(0,h.jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempt WHERE order_id=?",Integer.class,other.order()));
            assertFalse(pending.isDone());h.save("Q01-before-commit.json",h.snapshot(f));release.countDown();assertEquals(202,pending.get(12,TimeUnit.SECONDS).status());
        } finally { release.countDown();h.runtime.hooks.clear();executor.shutdownNow(); }
        result(id,BASE.plusSeconds(1));payment().settle(id);assertPayment(id,"SUCCESS","REVERSED");
        h.assertCounts(f,2,2,3,0);done("Q01","Concurrent/sequential same key, closed replay, changed-order conflict, in-flight new-key rejection, independent SQL sees no uncommitted acceptance",f);
    }
    void q02() throws Exception {
        var f=h.fixture("Q02",1);var held=hold(f,"q02-booking-key");h.runtime.plan=new RuntimeServices.SimulationPlan("FAILED",100);
        long failed=attempt(held.order(),"q02-failed-key");result(failed,BASE.plusSeconds(1));payment().settle(failed);assertPayment(failed,"FAILED","NO_CHARGE");
        var failureResult=h.json.valueToTree(row(failed));assertEquals("HOLD",state(held.order()));
        h.runtime.plan=new RuntimeServices.SimulationPlan("SUCCESS",100);long success=attempt(held.order(),"q02-success-key");
        result(success,BASE.plusSeconds(2));payment().settle(success);assertPayment(success,"SUCCESS","APPLIED");
        var successResult=h.json.valueToTree(row(success));
        h.runtime.plan=new RuntimeServices.SimulationPlan("FAILED",0);h.runtime.fixedTime=BASE.plusSeconds(1900);
        for(int i=0;i<3;i++){payment().generate(failed);payment().settle(failed);payment().generate(success);payment().settle(success);}
        assertEquals(failureResult,h.json.valueToTree(row(failed)));assertEquals(successResult,h.json.valueToTree(row(success)));
        assertEquals("PAID",state(held.order()));h.assertCounts(f,1,1,1,0);
        done("Q02","FAILED/NO_CHARGE permits a new key; old failure and success cannot be rewritten by replay or generation timeout",f);
    }
    void q03() throws Exception {
        var f=h.fixture("Q03-paid",1);var held=hold(f,"q03-booking-key");long id=attempt(held.order(),"q03-payment-key");result(id,BASE.plusSeconds(1));payment().settle(id);
        assertEquals("PAID_CANCEL_DISABLED",cancel(held.order()).body().path("code").asText());
        h.runtime.fixedTime=BASE.plusSeconds(700);h.app.getBean(OrderExpiryScanner.class).scanOnce();h.app.getBean(AllocationService.class).process(held.request());
        assertEquals("PAID",state(held.order()));h.assertCounts(f,1,1,1,0);
        var expired=h.fixture("Q03-expired-entry",1);var e=hold(expired,"q03-expire-booking");h.runtime.fixedTime=BASE.plusSeconds(600);
        assertEquals("PAYMENT_DEADLINE_EXCEEDED",pay(e.order(),"q03-expire-payment").body().path("code").asText());
        assertEquals("EXPIRED",state(e.order()));h.assertCounts(expired,1,1,2,1);
        assertEquals(0,h.jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempt WHERE order_id=?",Integer.class,e.order()));
        assertEquals("ORDER_NOT_PAYABLE",pay(e.order(),"q03-closed-payment").body().path("code").asText());
        done("Q03","Paid cancel denied; scanner/T2 leave PAID stock intact; overdue P0 commits C before 409 and creates no attempt",f,expired);
    }
    void q04() throws Exception {
        var used=new ArrayList<Fixture>();
        for(long offset:List.of(-1L,0L,1L)) {
            var f=h.fixture("Q04-boundary-"+(offset+1),1);used.add(f);var held=hold(f,"q04-book-key");long id=attempt(held.order(),"q04-pay-key");
            result(id,BASE.plusSeconds(1));settle(id,BASE.plusSeconds(600).plusMillis(offset));
            assertPayment(id,"SUCCESS",offset<0?"APPLIED":"REVERSED");assertEquals(offset<0?"PAID":"EXPIRED",state(held.order()));
            h.assertCounts(f,1,1,offset<0?1:2,offset<0?0:1);
        }
        var f=h.fixture("Q04-lock",1);used.add(f);var held=hold(f,"q04-lock-book");long id=attempt(held.order(),"q04-lock-pay");result(id,BASE.plusSeconds(1));
        h.runtime.fixedTime=BASE.plusSeconds(599);var reached=new CountDownLatch(1);var clockReads=new AtomicInteger();
        h.runtime.hooks.put("CLOSE_SESSION_LOCKED",o->{if(o==held.order())reached.countDown();});h.runtime.hooks.put("CLOCK_AFTER_LOCKS",a->clockReads.incrementAndGet());
        var executor=Executors.newSingleThreadExecutor();
        try(var connection=h.probe.getConnection()) {
            connection.setAutoCommit(false);try(var q=connection.prepareStatement("SELECT id FROM ticket_tier WHERE id=? FOR UPDATE")){q.setLong(1,f.tier());try(var rows=q.executeQuery()){assertTrue(rows.next());}}
            var settling=executor.submit(()->payment().settle(id));latch(reached);
            BookingHarness.await(()->h.jdbc.queryForList("SHOW PROCESSLIST").stream().anyMatch(r->r.get("Info")!=null && r.get("Info").toString().contains("ticket_tier") && r.get("Info").toString().contains("FOR UPDATE")));
            assertEquals(0,clockReads.get());h.save("Q04-blocked-before-database-clock.json",Map.of("realTierLockWait",true,"clockReads",clockReads.get(),"processingTimeBefore",time(BASE.plusSeconds(599))));
            h.runtime.fixedTime=BASE.plusSeconds(600);connection.rollback();settling.get(12,TimeUnit.SECONDS);assertTrue(clockReads.get()>0);
        } finally {h.runtime.hooks.clear();executor.shutdownNow();}
        assertPayment(id,"SUCCESS","REVERSED");h.assertCounts(f,1,1,2,1);
        done("Q04","D-1ms/D/D+1ms and actual database lock wait crossing controlled D; SELECT UTC_TIMESTAMP occurs only after required locks",used);
    }
    void q05() throws Exception {
        var used=new ArrayList<Fixture>();
        for(boolean payFirst:List.of(false,true)) {
            var f=h.fixture("Q05-"+(payFirst?"payment":"cancel"),1);used.add(f);var held=hold(f,"q05-book-key");long id=attempt(held.order(),"q05-pay-key");result(id,BASE.plusSeconds(1));
            var winnerLocked=new CountDownLatch(1);var loserStarted=new CountDownLatch(1);var release=new CountDownLatch(1);
            String boundary=payFirst?"P2_LOCKS_ACQUIRED":"CLOSE_LOCKS_ACQUIRED";
            h.runtime.hooks.put(boundary,x->{winnerLocked.countDown();latch(release);});
            var executor=Executors.newFixedThreadPool(2);
            try {
                var winner=executor.submit(()->{if(payFirst){payment().settle(id);return null;}return cancel(held.order());});latch(winnerLocked);
                var loser=executor.submit(()->{loserStarted.countDown();if(payFirst){return cancel(held.order());}payment().settle(id);return null;});latch(loserStarted);
                release.countDown();var win=winner.get(12,TimeUnit.SECONDS);var lose=loser.get(12,TimeUnit.SECONDS);
                if(payFirst){assertEquals(409,lose.status());}else{assertEquals(200,win.status());}
            } finally {release.countDown();h.runtime.hooks.clear();executor.shutdownNow();}
            assertPayment(id,"SUCCESS",payFirst?"APPLIED":"REVERSED");h.assertCounts(f,1,1,payFirst?1:2,payFirst?0:1);
        }
        var f=h.fixture("Q05-timeout-first",1);used.add(f);var held=hold(f,"q05-timeout-book");long id=attempt(held.order(),"q05-timeout-pay");
        h.runtime.fixedTime=BASE.plusSeconds(600);h.app.getBean(OrderExpiryScanner.class).scanOnce();payment().generate(id);payment().settle(id);
        var before=h.json.valueToTree(h.snapshot(f));payment().settle(id);cancel(held.order());assertEquals(before,h.json.valueToTree(h.snapshot(f)));
        assertPayment(id,"SUCCESS","REVERSED");h.assertCounts(f,1,1,2,1);
        done("Q05","Actual overlapping C/P2 calls with each lock winner; timeout-first late success; one release and one reversal",used);
    }
    void q06() throws Exception {
        var f=h.fixture("Q06",1);var first=hold(f,"q06-first-book");long old=attempt(first.order(),"q06-first-pay");cancel(first.order());
        String input=h.input(f).replace("\"tierId\":\""+f.tier()+"\"","\"tierId\":\""+f.otherTier()+"\"");
        var second=h.request("POST","/v1/booking-requests",input,"q06-cross-book",false,true);assertEquals(202,second.status());
        long newOrder=Long.parseLong(h.query(second.id()).body().path("order").path("orderId").asText());
        result(old,BASE.plusSeconds(1));payment().settle(old);assertPayment(old,"SUCCESS","REVERSED");
        var before=h.json.valueToTree(h.snapshot(f));for(int i=0;i<3;i++){payment().generate(old);payment().settle(old);cancel(first.order());h.app.getBean(AllocationService.class).process(first.request());}
        assertEquals(before,h.json.valueToTree(h.snapshot(f)));assertEquals(newOrder,h.jdbc.queryForObject("SELECT owner_id FROM user_session_quota WHERE user_id=? AND session_id=?",Long.class,f.userA(),f.session()));
        assertEquals("HOLD",state(newOrder));h.assertCounts(f,2,2,3,1);done("Q06","O1 reversal and repeated old work preserve O2 cross-tier quota and inventory",f);
    }
    void q07() throws Exception {
        var f=h.fixture("Q07",1);var held=hold(f,"q07-book-key");long id=attempt(held.order(),"q07-pay-key");h.runtime.fixedTime=BASE.plusSeconds(1);
        parallel(List.of(()->{payment().generate(id);return null;},()->{payment().generate(id);return null;}));
        assertEquals(1,h.jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE payment_attempt_id=?",Integer.class,id));
        assertEquals("HOLD",state(held.order()));h.save("Q07-result-before-D.json",h.snapshot(f));
        h.runtime.fixedTime=BASE.plusSeconds(601);h.runtime.recovery=false;
        var scanner=h.app.getBean(PaymentRecoveryScanner.class);scanner.generateOnce();scanner.settleOnce();scanner.settleOnce();h.runtime.recovery=false;
        assertPayment(id,"SUCCESS","REVERSED");h.assertCounts(f,1,1,2,1);
        done("Q07","Concurrent P1 creates one result/event; result recorded before D but recovered after D reverses, never backdates settlement",f);
    }
    void q08() throws Exception {
        var used=new ArrayList<Fixture>();
        for(String point:List.of("P0_AFTER_ATTEMPT","P0_BEFORE_COMMIT")) {
            var f=h.fixture("Q08-"+point,1);used.add(f);var held=hold(f,"q08-book-key");var before=h.json.valueToTree(h.snapshot(f));
            h.runtime.hooks.put(point,id->{throw new IllegalStateException("INJECTED_"+point);});assertEquals(503,pay(held.order(),"q08-pay-key").status());
            assertEquals(before,h.json.valueToTree(h.snapshot(f)));h.save(f.name()+"-rollback.json",h.snapshot(f));h.runtime.hooks.clear();
        }
        for(String point:List.of("P1_AFTER_RESULT","P1_BEFORE_COMMIT","P2_AFTER_ORDER","P2_AFTER_SETTLEMENT","P2_BEFORE_COMMIT","CLOSE_AFTER_LEDGER")) {
            var f=h.fixture("Q08-"+point,1);used.add(f);var held=hold(f,"q08-stage-book");long id=attempt(held.order(),"q08-stage-pay");
            boolean p1=point.startsWith("P1");if(!p1){result(id,BASE.plusSeconds(1));}
            h.runtime.fixedTime=point.startsWith("CLOSE")?BASE.plusSeconds(600):BASE.plusSeconds(1);
            var before=h.json.valueToTree(h.snapshot(f));h.runtime.hooks.put(point,a->{throw new IllegalStateException("INJECTED_"+point);});
            h.runtime.hooks.put("P2_BEFORE_FAILURE_RECORD",a->{throw new IllegalStateException("INJECTED_PAUSE_RETRY_AND_COUNT_FOR_ROLLBACK_INSPECTION");});
            if(p1){assertThrows(Exception.class,()->payment().generate(id));}else{payment().settle(id);}
            assertEquals(before,h.json.valueToTree(h.snapshot(f)));h.save(f.name()+"-rollback.json",h.snapshot(f));h.runtime.hooks.clear();
            payment().generate(id);payment().settle(id);assertPayment(id,"SUCCESS",point.startsWith("CLOSE")?"REVERSED":"APPLIED");
        }
        done("Q08","Eight injected write boundaries; independent probe SQL proves complete rollback with retries explicitly paused; includes C inside P2",used);
    }
    void q09() throws Exception {
        var f=h.fixture("Q09-ack",1);var held=hold(f,"q09-book-key");
        h.runtime.hooks.put("P0_COMMITTED",a->{throw new IllegalStateException("INJECTED_APPLICATION_P0_ACK_LOSS");});
        assertEquals("PAYMENT_ACCEPTANCE_UNKNOWN",pay(held.order(),"q09-pay-key").body().path("code").asText());h.runtime.hooks.clear();
        long id=attempt(held.order(),"q09-pay-key");h.runtime.fixedTime=BASE.plusSeconds(1);
        h.runtime.hooks.put("P1_COMMITTED",a->{throw new IllegalStateException("INJECTED_APPLICATION_P1_ACK_LOSS");});assertThrows(Exception.class,()->payment().generate(id));h.runtime.hooks.clear();
        h.runtime.hooks.put("P2_COMMITTED",a->{throw new IllegalStateException("INJECTED_APPLICATION_P2_ACK_LOSS");});payment().settle(id);h.runtime.hooks.clear();
        payment().generate(id);payment().settle(id);assertPayment(id,"SUCCESS","APPLIED");
        assertEquals(0,h.jdbc.queryForObject("SELECT attempts FROM outbox_event WHERE payment_attempt_id=?",Integer.class,id));h.assertCounts(f,1,1,1,0);
        var wire=h.fixture("Q09-wire",1);var w=hold(wire,"q09-wire-book");var committed=new CountDownLatch(1);var release=new CountDownLatch(1);
        h.runtime.hooks.put("BEFORE_PAYMENT_RESPONSE",a->{committed.countDown();latch(release);});
        try(var socket=new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1",18083),3000);
            String request="POST /v1/orders/"+w.order()+"/payment-attempts HTTP/1.1\r\nHost: 127.0.0.1:18083\r\nAuthorization: Bearer "+h.runtime.token(false)+"\r\nIdempotency-Key: q09-wire-pay\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));socket.getOutputStream().flush();latch(committed);
            assertEquals(1,h.jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempt WHERE order_id=?",Integer.class,w.order()));h.save("Q09-before-TCP-reset.json",h.snapshot(wire));
            socket.setSoLinger(true,0);socket.close();
        } finally {h.runtime.hooks.clear();release.countDown();}
        long wireId=attempt(w.order(),"q09-wire-pay");result(wireId,BASE.plusSeconds(1));payment().settle(wireId);assertPayment(wireId,"SUCCESS","APPLIED");
        done("Q09","Labelled application ACK injections at P0/P1/P2 vs actual client TCP reset after durable P0 before response; original key recovers without duplicate",f,wire);
    }
    Process child(Fixture f,long attempt,String mode) throws Exception {
        String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        String options="-Xmx256m\n-Dfile.encoding=UTF-8\n-cp\n\""+cp.replace('\\','/')+"\"\ncom.ticketing.booking.fixture.PaymentRecoveryProcess\n"+h.runId+"\n"+mode+"\n"+f.userA()+"\n"+f.userB()+"\n"+attempt+"\n";
        Path args=h.evidence.resolve("Q10-"+mode+"-args.txt");Files.writeString(args,options);
        var child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"@"+args)
            .directory(Bootstrap.ROOT.toFile()).redirectErrorStream(true).redirectOutput(h.evidence.resolve("Q10-"+mode+"-process.log").toFile()).start();
        h.log(Map.of("process",mode,"pid",child.pid(),"attemptId",Long.toString(attempt)));
        try {BookingHarness.await(()->Files.exists(h.evidence.resolve("Q10-"+mode+"-ready.json")) || !child.isAlive());
            assertTrue(Files.exists(h.evidence.resolve("Q10-"+mode+"-ready.json")),"Child failed startup");}
        catch(Throwable e){if(child.isAlive())child.destroyForcibly().waitFor(5,TimeUnit.SECONDS);throw e;}return child;
    }
    void stop(Process child) throws Exception {
        if(child!=null && child.isAlive()) {
            child.getOutputStream().write("STOP\n".getBytes(StandardCharsets.UTF_8));child.getOutputStream().flush();
            if(!child.waitFor(8,TimeUnit.SECONDS)){child.destroyForcibly().waitFor(5,TimeUnit.SECONDS);fail("Own child failed graceful stop");}assertEquals(0,child.exitValue());
        }
    }
    void q10() throws Exception {
        var f=h.fixture("Q10",1);var held=hold(f,"q10-book-key");long id=attempt(held.order(),"q10-pay-key");h.stopApp();Process crash=null,recover=null;
        try {
            crash=child(f,id,"crash");assertTrue(crash.waitFor(10,TimeUnit.SECONDS));assertEquals(71,crash.exitValue());
            assertEquals("SUCCESS",row(id).get("result_status"));assertEquals("UNRESOLVED",row(id).get("settlement_status"));assertEquals("HOLD",state(held.order()));h.save("Q10-before-restart.json",h.snapshot(f));
            recover=child(f,id,"recover");assertNotEquals(crash.pid(),recover.pid());BookingHarness.await(()->"PAID".equals(state(held.order())));
            assertPayment(id,"SUCCESS","APPLIED");h.assertCounts(f,1,1,1,0);
            h.save("Q10-recovery-summary.json",Map.of("crashPid",crash.pid(),"recoveryPid",recover.pid(),"exitCode",71,"attemptId",Long.toString(id),"databaseRestarted",false,"expiredRecoveryCompanion","Q07 controlled result before D / settlement after D"));
            done("Q10","Actual application exits after P1 physical commit; a new PID recovers PAID. Q07 separately covers expired recovery",f);
        } finally {stop(recover);stop(crash);h.startApp();}
    }
    void q11() throws Exception {
        var f=h.fixture("Q11-generator",1);var held=hold(f,"q11-book-key");h.runtime.plan=new RuntimeServices.SimulationPlan("SUCCESS",1_200_000);
        long id=attempt(held.order(),"q11-pay-key");h.runtime.fixedTime=BASE.plusSeconds(1800);
        parallel(List.of(()->{payment().generate(id);return null;},()->{payment().generate(id);return null;}));
        assertEquals("FAILED",row(id).get("result_status"));assertEquals("SIM_GENERATION_EXPIRED",row(id).get("result_reason"));
        assertEquals(1,h.jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE payment_attempt_id=?",Integer.class,id));payment().settle(id);assertPayment(id,"FAILED","NO_CHARGE");h.assertCounts(f,1,1,2,1);
        var exhausted=h.fixture("Q11-exhausted",1);var eh=hold(exhausted,"q11-exhaust-book");long eid=attempt(eh.order(),"q11-exhaust-pay");result(eid,BASE.plusSeconds(1));
        h.runtime.hooks.put("P2_AFTER_ORDER",a->{if(a==eid)throw new IllegalStateException("INJECTED_SIX_FAILURES");});
        var backoff=new ArrayList<Map<String,Object>>();
        for(int i=1;i<=6;i++) {
            Instant now=h.runtime.fixedTime;payment().settle(eid);var event=h.jdbc.queryForMap("SELECT * FROM outbox_event WHERE payment_attempt_id=?",eid);
            assertEquals(i,number(event,"attempts"));assertEquals("HOLD",state(eh.order()));
            if(i<6){long delay=Duration.between(now,instant(event.get("next_attempt_at"))).toMillis();long base=(1L<<(i-1))*1000;assertTrue(delay>=base && delay<=base*1.2);backoff.add(Map.of("failure",i,"delayMillis",delay));h.runtime.fixedTime=instant(event.get("next_attempt_at"));}
            else{assertEquals("DEAD",event.get("state"));}
        }
        h.runtime.hooks.clear();assertPayment(eid,"SUCCESS","REVIEW");assertEquals("RETRY_EXHAUSTED",query(eid).body().path("reviewReason").asText());
        h.runtime.fixedTime=BASE.plusSeconds(600);h.app.getBean(OrderExpiryScanner.class).scanOnce();assertEquals("EXPIRED",state(eh.order()));assertPayment(eid,"SUCCESS","REVIEW");
        h.assertCounts(exhausted,1,1,2,1);h.save("Q11-six-failures.json",backoff);
        var stale=h.fixture("Q11-stale-generation",1);var sh=hold(stale,"q11-stale-book");long sid=attempt(sh.order(),"q11-stale-pay");result(sid,BASE.plusSeconds(1));
        h.runtime.hooks.put("P2_AFTER_ORDER",a->{throw new IllegalStateException("INJECTED_FIRST_SETTLER_FAILURE");});
        h.runtime.hooks.put("P2_BEFORE_FAILURE_RECORD",a->{h.runtime.hooks.clear();payment().settle(a);});payment().settle(sid);
        assertPayment(sid,"SUCCESS","APPLIED");assertEquals(0,h.jdbc.queryForObject("SELECT attempts FROM outbox_event WHERE payment_attempt_id=?",Integer.class,sid));
        done("Q11","Generation deadline competes for one immutable result; six persisted failures/backoff become DEAD/REVIEW, independent C releases; stale failure cannot overwrite a winning settlement",f,exhausted,stale);
    }
    void q12() throws Exception {
        var f=h.fixture("Q12",1);var held=hold(f,"q12-book-key");String post="/v1/orders/"+held.order()+"/payment-attempts";
        assertEquals(401,h.request("POST","/v1/orders/invalid/payment-attempts","not-json",null,false,false).status());
        assertEquals(404,h.request("POST",post,"{}","q12-hidden-key",true,true).status());
        assertEquals(404,pay(Long.MAX_VALUE,"q12-absent-key").status());
        for(String body:List.of("","null","[]","1","{} {}","{\"userId\":1}","{\"amountCent\":1}","{\"resultStatus\":\"SUCCESS\"}","{\"plannedAt\":\"2030\"}","{\"scenario\":\"SUCCESS\"}","{\"x\":1,\"x\":2}"))
            assertEquals(400,h.request("POST",post,body,"q12-invalid-key",false,true).status());
        assertEquals(400,h.request("POST",post+"?result=SUCCESS","{}","q12-query-key",false,true).status());
        for(String key:List.of("","short","has spaces 123","a".repeat(65)))assertEquals(400,pay(held.order(),key).status());
        h.runtime.simulation=false;assertEquals("SIMULATOR_DISABLED",pay(held.order(),"q12-disabled-key").body().path("code").asText());h.runtime.simulation=true;
        // Explicit switch is independent of the test implementation's enable flag.
        h.stopApp();
        try {h.app=new org.springframework.boot.SpringApplication(com.ticketing.booking.application.BookingApplication.class).run("--spring.profiles.active=biz01-it","--biz.runId="+h.runId,"--biz.simulation.enabled=false");
            assertEquals("SIMULATOR_DISABLED",pay(held.order(),"q12-switch-off-key").body().path("code").asText());}
        finally{h.stopApp();h.startApp();}
        long id=attempt(held.order(),"q12-valid-key");
        assertEquals(401,h.request("GET","/v1/payment-attempts/"+id,null,null,false,false).status());
        assertEquals(404,h.request("GET","/v1/payment-attempts/"+id,null,null,true,true).status());
        assertEquals(404,query(Long.MAX_VALUE).status());
        for(String invalid:List.of("0","01","-1","bad","9223372036854775808"))assertEquals(400,h.request("GET","/v1/payment-attempts/"+invalid,null,null,false,true).status());
        assertEquals(400,h.request("GET","/v1/payment-attempts/"+id+"?result=SUCCESS",null,null,false,true).status());
        h.runtime.simulation=false;h.runtime.fixedTime=BASE.plusSeconds(1);h.runtime.recovery=false;h.app.getBean(PaymentRecoveryScanner.class).generateOnce();h.runtime.recovery=false;
        assertEquals("PENDING",row(id).get("result_status"));h.runtime.simulation=true;
        assertThrows(IllegalArgumentException.class,()->new RuntimeServices.SimulationPlan("SUCCESS",1_200_001));
        var wrong=MigrationTest.environment();wrong.setActiveProfiles("biz01");assertThrows(IllegalStateException.class,()->Bootstrap.prepare(wrong));
        result(id,BASE.plusSeconds(1));payment().settle(id);h.assertCounts(f,1,1,1,0);
        done("Q12","Identity-first and own routes, strict empty body/key/query, no client outcome/time/money; explicit switch and fixture switch independently disable; wrong profile rejected",f);
    }
    void q13() throws Exception {
        var f=h.fixture("Q13-constraints",1);var held=hold(f,"q13-book-key");long id=attempt(held.order(),"q13-pay-key");
        var rejected=new ArrayList<String>();
        Map<String,String> invalid=new LinkedHashMap<>();
        invalid.put("result fields absent","UPDATE payment_attempt SET result_status='SUCCESS' WHERE id=?");
        invalid.put("pending cannot apply","UPDATE payment_attempt SET settlement_status='APPLIED',settled_at=created_at WHERE id=?");
        invalid.put("pending cannot review","UPDATE payment_attempt SET settlement_status='REVIEW',review_reason='bad' WHERE id=?");
        invalid.put("bad plan deadline","UPDATE payment_attempt SET planned_at=created_at+INTERVAL 21 MINUTE WHERE id=?");
        invalid.put("bad generation deadline","UPDATE payment_attempt SET resolution_deadline=created_at+INTERVAL 31 MINUTE WHERE id=?");
        invalid.put("bad money","UPDATE payment_attempt SET amount_cent=0 WHERE id=?");
        invalid.put("wrong owner FK","UPDATE payment_attempt SET user_id="+f.userB()+" WHERE id=?");
        invalid.put("no dangling order FK","UPDATE payment_attempt SET order_id=9223372036854775807 WHERE id=?");
        for(var entry:invalid.entrySet()){assertThrows(Exception.class,()->h.jdbc.update(entry.getValue(),id));rejected.add(entry.getKey());}
        assertThrows(Exception.class,()->h.jdbc.update("UPDATE booking_order SET status='PAID' WHERE id=?",held.order()));rejected.add("PAID without paid linkage");
        result(id,BASE.plusSeconds(1));
        assertThrows(Exception.class,()->h.jdbc.update("UPDATE outbox_event SET booking_request_id=? WHERE payment_attempt_id=?",held.request(),id));rejected.add("outbox double typed source");
        assertThrows(Exception.class,()->h.jdbc.update("UPDATE outbox_event SET aggregate_id=aggregate_id+1 WHERE payment_attempt_id=?",id));rejected.add("outbox mismatched aggregate");
        assertThrows(Exception.class,()->h.jdbc.update("UPDATE outbox_event SET payment_attempt_id=NULL WHERE payment_attempt_id=?",id));rejected.add("outbox missing source");
        assertThrows(Exception.class,()->h.jdbc.update("UPDATE payment_attempt SET settlement_status='REVERSED',settled_at=result_at WHERE id=?",id));rejected.add("reversal missing compensation");
        payment().settle(id);assertPayment(id,"SUCCESS","APPLIED");
        // A structurally valid second SUCCESS is inserted only as a test-owned constraint probe.
        String copy="INSERT INTO payment_attempt(order_id,user_id,session_id,idempotency_key,attempt_no,amount_cent,currency,created_at,planned_status,planned_at,resolution_deadline,result_status,result_id,result_at,result_reason,result_digest,settlement_status,settled_at) SELECT order_id,user_id,session_id,?,2,amount_cent,currency,created_at,planned_status,planned_at,resolution_deadline,result_status,?,result_at,result_reason,result_digest,?,? FROM payment_attempt WHERE id=?";
        assertThrows(Exception.class,()->h.jdbc.update(copy,"q13-duplicate-applied",UUID.randomUUID().toString(),"APPLIED",row(id).get("settled_at"),id));rejected.add("at most one APPLIED per order");
        assertThrows(Exception.class,()->h.jdbc.update(copy,"q13-duplicate-result",row(id).get("result_id"),"UNRESOLVED",null,id));rejected.add("unique immutable result ID");
        h.save("Q13-rejected-constraints.json",rejected);h.assertCounts(f,1,1,1,0);
        var used=new ArrayList<Fixture>();used.add(f);
        for(String corruption:List.of("digest","amount","reverse-link")) {
            var bad=h.fixture("Q13-trusted-"+corruption,1);used.add(bad);var bh=hold(bad,"q13-bad-book");long bid=attempt(bh.order(),"q13-bad-pay");result(bid,BASE.plusSeconds(1));
            if(corruption.equals("digest")){h.jdbc.update("UPDATE payment_attempt SET result_digest=UNHEX(SHA2('test-corruption',256)) WHERE id=?",bid);}
            else if(corruption.equals("amount")){h.jdbc.update("UPDATE payment_attempt SET amount_cent=amount_cent+1 WHERE id=?",bid);}
            else{h.jdbc.update("UPDATE booking_order SET current_attempt_id=? WHERE id=?",id,bh.order());}
            var stock=h.jdbc.queryForMap("SELECT * FROM ticket_tier WHERE id=?",bad.tier());var ledger=h.jdbc.queryForList("SELECT * FROM inventory_ledger WHERE order_id=?",bh.order());
            payment().settle(bid);assertPayment(bid,"SUCCESS","REVIEW");assertEquals("HOLD",state(bh.order()));
            assertEquals(stock,h.jdbc.queryForMap("SELECT * FROM ticket_tier WHERE id=?",bad.tier()));assertEquals(ledger,h.jdbc.queryForList("SELECT * FROM inventory_ledger WHERE order_id=?",bh.order()));
            assertEquals("TRUSTED_RESULT_INCONSISTENT",query(bid).body().path("reviewReason").asText());
        }
        done("Q13","Fifteen SQL negative constraints including typed Outbox and one APPLIED; deliberate digest/amount/reverse-link corruption diagnosed REVIEW, never healed or paid",used);
    }
    void q14() throws Exception {
        var used=new ArrayList<Fixture>();var badIds=new HashSet<Long>();
        for(int i=0;i<21;i++) {
            var f=h.fixture("Q14-cursor-"+i,1);used.add(f);var held=hold(f,"q14-book-key");long id=attempt(held.order(),"q14-pay-key");if(i<20)badIds.add(id);
        }
        long good=h.jdbc.queryForObject("SELECT id FROM payment_attempt WHERE session_id=?",Long.class,used.get(20).session());
        h.runtime.fixedTime=BASE.plusSeconds(300);h.runtime.recovery=false;
        var scanner=new PaymentRecoveryScanner(h.store,h.runtime,payment(),h.app.getBean(Diagnostics.class));
        var observed=new ConcurrentLinkedQueue<Long>();
        h.runtime.hooks.put("P1_LOCKS_ACQUIRED",id->{observed.add(id);if(badIds.contains(id))throw new IllegalStateException("INJECTED_BOUNDED_GENERATOR_FAILURE");});
        for(int i=0;i<8 && "PENDING".equals(row(good).get("result_status"));i++){int before=observed.size();scanner.generateOnce();assertTrue(observed.size()-before<=20);}
        assertEquals("SUCCESS",row(good).get("result_status"));for(long id:badIds)assertEquals("PENDING",row(id).get("result_status"));
        h.runtime.hooks.clear();for(int i=0;i<8;i++){scanner.generateOnce();scanner.settleOnce();}h.runtime.recovery=false;
        for(var f:used){h.runtime.userA=f.userA();h.runtime.userB=f.userB();long id=h.jdbc.queryForObject("SELECT id FROM payment_attempt WHERE session_id=?",Long.class,f.session());assertPayment(id,"SUCCESS","APPLIED");h.assertCounts(f,1,1,1,0);}
        // Original booking scanner sees only its own event type even when payment events remain READY.
        var late=h.fixture("Q14-routing",1);used.add(late);var held=hold(late,"q14-routing-book");long id=attempt(held.order(),"q14-routing-pay");result(id,BASE.plusSeconds(300));
        var before=h.json.valueToTree(row(id));h.app.getBean(RecoveryScanner.class).scanOnce();assertEquals(before,h.json.valueToTree(row(id)));
        assertEquals("READY",h.jdbc.queryForObject("SELECT state FROM outbox_event WHERE payment_attempt_id=?",String.class,id));payment().settle(id);assertPayment(id,"SUCCESS","APPLIED");
        h.save("Q14-bounded-routing.json",Map.of("candidateLimit",20,"generatorFailures",badIds,"goodId",Long.toString(good),"visited",observed,"paymentSettledAfterRequest120Seconds",true,"routes",6));
        done("Q14","Independent bounded cursor passes twenty failing generators; payment obligations survive original request 120s; booking scanner never consumes payment events",used);
    }
}
