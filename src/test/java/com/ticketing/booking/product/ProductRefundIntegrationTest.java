package com.ticketing.booking.product;

import com.ticketing.booking.config.Diagnostics;
import com.ticketing.booking.config.RuntimeServices;
import com.ticketing.booking.domain.ApiFailure;
import com.ticketing.booking.persistence.BookingStore;
import com.ticketing.booking.service.OrderCloseService;
import com.ticketing.booking.service.PaymentService;
import com.ticketing.booking.service.RefundService;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import static com.ticketing.booking.domain.Values.instant;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP, MySQL and Redis; test-owned service proxies inject faults without public fixture routes. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductRefundIntegrationTest {
    private ProductLiveHarness h;
    private ProductLiveHarness.Token buyer, waiting, newcomer;
    private record Paid(ProductLiveHarness.Catalogue catalogue, ProductLiveHarness.Token user,
            long order, long attempt, Map<String,Object> payment) { }

    @BeforeAll void open() throws Exception {
        h=ProductLiveHarness.open();
        // Reuse identities across independent new sessions, preserving real SMS/IP rate limits.
        buyer=h.user();waiting=h.user();newcomer=h.user();
    }
    @AfterAll void close() { if(h!=null) { h.close(); } }
    private static String key() { return UUID.randomUUID().toString(); }
    private String refundPath(long order) { return "/v1/orders/"+order+"/refunds"; }
    private RefundService refunds() { return h.ctx.getBean(RefundService.class); }
    private RefundService service(RuntimeServices runtime) {
        return new RefundService(h.ctx.getBean(BookingStore.class),runtime,h.ctx.getBean(OrderCloseService.class),
            h.ctx.getBean(Diagnostics.class),h.ctx.getBean(PlatformTransactionManager.class),true);
    }
    private RuntimeServices runtime(Instant clock, BiConsumer<String,Long> hook) {
        RuntimeServices actual=h.ctx.getBean(RuntimeServices.class);
        return (RuntimeServices)Proxy.newProxyInstance(RuntimeServices.class.getClassLoader(),new Class<?>[]{RuntimeServices.class},(proxy,method,args)-> {
            if(method.getName().equals("afterLocks") && clock!=null) {
                if(hook!=null) { hook.accept("TEST_CLOCK_READ",0L); }
                return clock;
            }
            if(method.getName().equals("at") && args!=null && args.length==2 && hook!=null) {
                hook.accept((String)args[0],((Number)args[1]).longValue());
            }
            try { return method.invoke(actual,args); }
            catch(InvocationTargetException failure) { throw failure.getCause(); }
        });
    }
    private Paid paid(ProductLiveHarness.Token user, ProductLiveHarness.Catalogue catalogue) throws Exception {
        long order=h.book(user,catalogue);
        var accepted=h.call("POST","/v1/orders/"+order+"/payment-attempts",user,Map.of(),key());
        assertEquals(202,accepted.status(),accepted.body().toString());
        long attempt=accepted.body().path("attemptId").asLong();assertTrue(attempt>0);
        h.flushWork();
        assertEquals("PAID",h.call("GET","/v1/orders/"+order,user,null,null).body().path("orderStatus").asText());
        return new Paid(catalogue,user,order,attempt,paymentSnapshot(attempt));
    }
    private Paid paid() throws Exception { return paid(buyer,h.catalogue(1)); }
    private Map<String,Object> paymentSnapshot(long attempt) {
        return h.jdbc.queryForMap("SELECT result_id,result_status,result_at,result_digest,amount_cent,currency,settlement_status,settled_at FROM payment_attempt WHERE id=?",attempt);
    }
    private void originalPaymentUnchanged(Paid paid) throws Exception {
        var after=paymentSnapshot(paid.attempt());
        for(String field:paid.payment().keySet()) {
            if(field.equals("result_digest")) { assertArrayEquals((byte[])paid.payment().get(field),(byte[])after.get(field)); }
            else { assertEquals(paid.payment().get(field),after.get(field),field); }
        }
        var visible=h.call("GET","/v1/payment-attempts/"+paid.attempt(),paid.user(),null,null);
        assertEquals(200,visible.status(),visible.body().toString());
        assertEquals("SUCCESS",visible.body().path("resultStatus").asText());
        assertEquals("APPLIED",visible.body().path("settlementStatus").asText());
    }
    private long accept(Paid paid, String requestKey) throws Exception {
        var accepted=h.call("POST",refundPath(paid.order()),paid.user(),Map.of("reason","行程变更，申请全额退款"),requestKey);
        assertEquals(202,accepted.status(),accepted.body().toString());
        long id=accepted.body().path("refundId").asLong();assertTrue(id>0);
        assertEquals(paid.order(),accepted.body().path("orderId").asLong());
        assertEquals("/v1/refunds/"+id,accepted.body().path("statusUrl").asText());
        assertEquals("PENDING",accepted.body().path("status").asText());
        assertTrue(accepted.body().path("simulated").asBoolean());
        return id;
    }
    private void pendingOwnsInventory(Paid paid, long refund) {
        assertEquals("REFUNDING",h.jdbc.queryForObject("SELECT status FROM booking_order WHERE id=?",String.class,paid.order()));
        assertEquals(refund,h.jdbc.queryForObject("SELECT current_refund_id FROM booking_order WHERE id=?",Long.class,paid.order()));
        assertEquals(0,h.jdbc.queryForObject("SELECT available FROM ticket_tier WHERE id=?",Integer.class,paid.catalogue().tierId()));
        var owner=h.jdbc.queryForMap("SELECT owner_type,owner_id FROM user_session_quota WHERE user_id=? AND session_id=?",paid.user().userId(),paid.catalogue().sessionId());
        assertEquals("ORDER",owner.get("owner_type"));assertEquals(paid.order(),((Number)owner.get("owner_id")).longValue());
        assertEquals(0,h.jdbc.queryForObject("SELECT COUNT(*) FROM inventory_ledger WHERE order_id=? AND action='RELEASE'",Integer.class,paid.order()));
        h.invariant(paid.catalogue());
    }
    private void succeed(Paid paid, long refund) throws Exception {
        refunds().settle(refund);
        var result=h.call("GET","/v1/refunds/"+refund,paid.user(),null,null);
        assertEquals(200,result.status(),result.body().toString());
        assertEquals("SUCCEEDED",result.body().path("status").asText());
        assertEquals("REFUNDED",result.body().path("orderStatus").asText());
        assertEquals(9900,result.body().path("amountCent").asInt());assertEquals("CNY",result.body().path("currency").asText());
        assertFalse(result.body().path("completedAt").isNull());assertFalse(result.body().path("completedAt").asText().isBlank());
        assertEquals(1,h.jdbc.queryForObject("SELECT COUNT(*) FROM inventory_ledger WHERE order_id=? AND action='RELEASE'",Integer.class,paid.order()));
        originalPaymentUnchanged(paid);h.invariant(paid.catalogue());
    }

    @Test void refundKeepsPaymentHistoryAndReleasesOnlyAfterCompletion() throws Exception {
        var paid=paid();String requestKey=key();long refund=accept(paid,requestKey);
        pendingOwnsInventory(paid,refund);
        var pending=h.call("GET","/v1/refunds/"+refund,paid.user(),null,null);
        assertEquals(200,pending.status(),pending.body().toString());
        assertEquals("PENDING",pending.body().path("status").asText());assertTrue(pending.body().path("completedAt").isNull());
        assertEquals(9900,pending.body().path("amountCent").asInt());assertEquals("行程变更，申请全额退款",pending.body().path("reason").asText());
        assertEquals(409,h.call("POST","/v1/orders/"+paid.order()+"/cancel",buyer,Map.of(),null).status());
        assertEquals(409,h.call("POST","/v1/orders/"+paid.order()+"/payment-attempts",buyer,Map.of(),key()).status());
        succeed(paid,refund);
        var order=h.call("GET","/v1/orders/"+paid.order(),buyer,null,null).body();
        assertEquals(refund,order.path("currentRefundId").asLong());assertFalse(order.path("refundedAt").isNull());
        assertEquals(paid.attempt(),order.path("paidAttemptId").asLong());assertFalse(order.path("paidAt").isNull());
        var replay=h.call("POST",refundPath(paid.order()),buyer,Map.of("reason","行程变更，申请全额退款"),requestKey);
        assertEquals(202,replay.status(),replay.body().toString());assertEquals(refund,replay.body().path("refundId").asLong());
        assertEquals("SUCCEEDED",replay.body().path("status").asText());
    }

    @Test void concurrentSubmissionsAndSettlementProduceOneRefundAndOneRelease() throws Exception {
        var paid=paid();String requestKey=key();var executor=Executors.newFixedThreadPool(4);
        long refund;
        try {
            var submissions=new ArrayList<Callable<ProductLiveHarness.Reply>>();
            for(int i=0;i<8;i++) { submissions.add(()->h.call("POST",refundPath(paid.order()),buyer,Map.of("reason","重复点击退款"),requestKey)); }
            var replies=executor.invokeAll(submissions,30,TimeUnit.SECONDS);refund=0;
            for(var future:replies) {
                var reply=future.get();assertEquals(202,reply.status(),reply.body().toString());
                long actual=reply.body().path("refundId").asLong();assertTrue(actual>0);
                if(refund==0) { refund=actual; } else { assertEquals(refund,actual); }
            }
            final long id=refund;var settlements=new ArrayList<Callable<Void>>();
            for(int i=0;i<8;i++) { settlements.add(()->{refunds().settle(id);return null;}); }
            for(var future:executor.invokeAll(settlements,30,TimeUnit.SECONDS)) { future.get(); }
        } finally { executor.shutdownNow(); }
        assertEquals(1,h.jdbc.queryForObject("SELECT COUNT(*) FROM refund_request WHERE order_id=?",Integer.class,paid.order()));
        succeed(paid,refund);
        assertEquals(1,h.jdbc.queryForObject("SELECT available FROM ticket_tier WHERE id=?",Integer.class,paid.catalogue().tierId()));
    }

    @Test void ownershipStrictInputsAndServerCalculatedAmountAreEnforced() throws Exception {
        var paid=paid();var reason=Map.of("reason","测试退款权限");String path=refundPath(paid.order());
        assertEquals(401,h.call("POST",path,null,reason,key()).status());
        assertEquals(404,h.call("POST",path,waiting,reason,key()).status());
        for(var extra:Map.<String,Object>of("amountCent",1,"currency","USD","orderId",Long.toString(paid.order())).entrySet()) {
            var body=new java.util.HashMap<String,Object>(reason);body.put(extra.getKey(),extra.getValue());
            assertEquals(400,h.call("POST",path,buyer,body,key()).status(),extra.getKey());
        }
        assertEquals(400,h.call("POST",path,buyer,reason,null).status());
        assertEquals(400,h.call("POST",path,buyer,Map.of("reason","   "),key()).status());
        assertEquals(0,h.jdbc.queryForObject("SELECT COUNT(*) FROM refund_request WHERE order_id=?",Integer.class,paid.order()));
        long refund=accept(paid,key());
        assertEquals(401,h.call("GET","/v1/refunds/"+refund,null,null,null).status());
        assertEquals(404,h.call("GET","/v1/refunds/"+refund,waiting,null,null).status());
        assertEquals(403,h.call("GET","/v1/admin/refunds/"+refund,buyer,null,null).status());
        assertEquals(403,h.call("GET","/v1/admin/refunds?status=REVIEW",buyer,null,null).status());
        succeed(paid,refund);
    }

    @Test void idempotencyRejectsDifferentOrderDifferentReasonAndSecondKey() throws Exception {
        var first=paid();var second=paid();String requestKey=key();long refund=accept(first,requestKey);
        var changed=h.call("POST",refundPath(first.order()),buyer,Map.of("reason","改变退款原因"),requestKey);
        assertEquals(409,changed.status(),changed.body().toString());assertEquals("IDEMPOTENCY_CONFLICT",changed.body().path("code").asText());
        var another=h.call("POST",refundPath(second.order()),buyer,Map.of("reason","行程变更，申请全额退款"),requestKey);
        assertEquals(409,another.status(),another.body().toString());assertEquals("IDEMPOTENCY_CONFLICT",another.body().path("code").asText());
        var newKey=h.call("POST",refundPath(first.order()),buyer,Map.of("reason","行程变更，申请全额退款"),key());
        assertEquals(409,newKey.status(),newKey.body().toString());assertEquals("REFUND_ALREADY_REQUESTED",newKey.body().path("code").asText());
        assertEquals(1,h.jdbc.queryForObject("SELECT COUNT(*) FROM refund_request WHERE order_id=?",Integer.class,first.order()));
        assertEquals(0,h.jdbc.queryForObject("SELECT COUNT(*) FROM refund_request WHERE order_id=?",Integer.class,second.order()));
        succeed(first,refund);h.invariant(second.catalogue());
    }

    @Test void unpaidCancelledAndExpiredOrdersCannotBeRefunded() throws Exception {
        var c=h.catalogue(1);long held=h.book(buyer,c);var reason=Map.of("reason","未支付不能退款");
        assertEquals(409,h.call("POST",refundPath(held),buyer,reason,key()).status());
        assertEquals(200,h.call("POST","/v1/orders/"+held+"/cancel",buyer,Map.of(),null).status());
        assertEquals(409,h.call("POST",refundPath(held),buyer,reason,key()).status());
        long expired=h.book(buyer,c);
        // Only a newly created fixture receives controlled HOLD timestamps; old user orders are untouched.
        assertEquals(1,h.jdbc.update("UPDATE booking_order SET hold_created_at=UTC_TIMESTAMP(3)-INTERVAL 601 SECOND,pay_expires_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE id=? AND status='HOLD'",expired));
        h.ctx.getBean(OrderCloseService.class).expire(expired);
        assertEquals("EXPIRED",h.jdbc.queryForObject("SELECT status FROM booking_order WHERE id=?",String.class,expired));
        assertEquals(409,h.call("POST",refundPath(expired),buyer,reason,key()).status());
        assertEquals(0,h.jdbc.queryForObject("SELECT COUNT(*) FROM refund_request WHERE order_id IN (?,?)",Integer.class,held,expired));h.invariant(c);
    }

    @Test void refundWindowUsesTimeAfterBusinessLocksAndClosesExactlyAtStart() throws Exception {
        for(long offsetMillis:new long[]{-1,0,1}) {
            var paid=paid();Instant start=instant(h.jdbc.queryForObject("SELECT start_at FROM event_session WHERE id=?",java.time.LocalDateTime.class,paid.catalogue().sessionId()));
            var locksSeen=new AtomicInteger();
            var controlled=service(runtime(start.plusMillis(offsetMillis),(boundary,id)->{
                if(boundary.equals("R0_LOCKS_ACQUIRED") && id==paid.order()) { locksSeen.incrementAndGet(); }
                if(boundary.equals("TEST_CLOCK_READ")) { assertEquals(1,locksSeen.get(),"clock must be read after the business locks"); }
            }));
            if(offsetMillis<0) {
                var accepted=controlled.accept(buyer.userId(),paid.order(),key(),"开场前最后一毫秒");
                long refund=Long.parseLong(accepted.get("refundId").toString());assertTrue(refund>0);
                pendingOwnsInventory(paid,refund);
                // The request was accepted before start; completion may follow later.
                service(runtime(start.plusSeconds(1),null)).settle(refund);
                assertEquals("SUCCEEDED",h.jdbc.queryForObject("SELECT status FROM refund_request WHERE id=?",String.class,refund));
                originalPaymentUnchanged(paid);h.invariant(paid.catalogue());
            } else {
                var denied=assertThrows(ApiFailure.class,()->controlled.accept(buyer.userId(),paid.order(),key(),"开场后不可退款"));
                assertEquals(409,denied.status());assertEquals("REFUND_WINDOW_CLOSED",denied.code());
                assertEquals("PAID",h.jdbc.queryForObject("SELECT status FROM booking_order WHERE id=?",String.class,paid.order()));
                assertEquals(0,h.jdbc.queryForObject("SELECT COUNT(*) FROM refund_request WHERE order_id=?",Integer.class,paid.order()));
                h.invariant(paid.catalogue());
            }
            assertEquals(1,locksSeen.get(),"refund-window decision must follow the common business locks");
        }
    }

    @ParameterizedTest @ValueSource(strings={"R1_AFTER_STOCK","R1_BEFORE_COMMIT"})
    void settlementFailureRollsBackRefundOrderStockOwnerAndLedger(String boundary) throws Exception {
        var paid=paid();long refund=accept(paid,key());var injections=new AtomicInteger();
        var failing=service(runtime(null,(stage,id)->{
            if(stage.equals(boundary) && id==refund) { injections.incrementAndGet();throw new IllegalStateException("CONTROLLED_REFUND_TRANSACTION_FAILURE"); }
        }));
        failing.settle(refund);
        assertEquals(1,injections.get());
        var failed=h.jdbc.queryForMap("SELECT status,attempts,completed_at FROM refund_request WHERE id=?",refund);
        assertEquals("PENDING",failed.get("status"));assertEquals(1,((Number)failed.get("attempts")).intValue());assertNull(failed.get("completed_at"));
        pendingOwnsInventory(paid,refund);originalPaymentUnchanged(paid);
        assertEquals(1,h.jdbc.update("UPDATE refund_request SET next_attempt_at=UTC_TIMESTAMP(3) WHERE id=? AND status='PENDING'",refund));
        succeed(paid,refund);
    }

    @ParameterizedTest @ValueSource(strings={"R0_BEFORE_COMMIT","BEFORE_REFUND_RESPONSE"})
    void uncertainAcceptanceCanBeRetriedWithTheSameKeyWithoutDuplicatingMoney(String boundary) throws Exception {
        var paid=paid();String requestKey=key();var injected=new AtomicInteger();
        var failing=service(runtime(null,(stage,id)->{
            if(stage.equals(boundary)) { injected.incrementAndGet();throw new IllegalStateException("CONTROLLED_REFUND_ACCEPTANCE_FAILURE"); }
        }));
        var uncertain=assertThrows(ApiFailure.class,()->failing.accept(buyer.userId(),paid.order(),requestKey,"行程变更，申请全额退款"));
        assertEquals(503,uncertain.status());assertEquals("REFUND_ACCEPTANCE_UNKNOWN",uncertain.code());assertEquals(1,injected.get());
        boolean committed=boundary.equals("BEFORE_REFUND_RESPONSE");
        assertEquals(committed?1:0,h.jdbc.queryForObject("SELECT COUNT(*) FROM refund_request WHERE order_id=?",Integer.class,paid.order()));
        assertEquals(committed?"REFUNDING":"PAID",h.jdbc.queryForObject("SELECT status FROM booking_order WHERE id=?",String.class,paid.order()));
        if(!committed) { assertNull(h.jdbc.queryForObject("SELECT current_refund_id FROM booking_order WHERE id=?",Long.class,paid.order())); }
        long refund=accept(paid,requestKey);pendingOwnsInventory(paid,refund);succeed(paid,refund);
        assertEquals(1,h.jdbc.queryForObject("SELECT COUNT(*) FROM refund_request WHERE order_id=?",Integer.class,paid.order()));
    }

    @Test void staleFailureCannotConsumeTheRetryBudgetOfANewAdminRecoveryGeneration() throws Exception {
        var paid=paid();long refund=accept(paid,key());var reached=new CountDownLatch(1);var release=new CountDownLatch(1);
        var stale=service(runtime(null,(stage,id)->{
            if(id!=refund) { return; }
            if(stage.equals("R1_AFTER_STOCK")) { throw new IllegalStateException("CONTROLLED_STALE_REFUND_FAILURE"); }
            if(stage.equals("R1_BEFORE_FAILURE_RECORD")) {
                reached.countDown();
                try { assertTrue(release.await(30,TimeUnit.SECONDS),"stale worker release timed out"); }
                catch(InterruptedException failure) { Thread.currentThread().interrupt();throw new IllegalStateException(failure); }
            }
        }));
        var current=service(runtime(null,(stage,id)->{
            if(stage.equals("R1_AFTER_STOCK") && id==refund) { throw new IllegalStateException("CONTROLLED_CURRENT_REFUND_FAILURE"); }
        }));
        var executor=Executors.newSingleThreadExecutor();
        try {
            var worker=executor.submit(()->stale.settle(refund));
            assertTrue(reached.await(5,TimeUnit.SECONDS),"old worker did not reach failure persistence boundary");
            for(int count=1;count<=6;count++) {
                assertEquals(1,h.jdbc.update("UPDATE refund_request SET next_attempt_at=UTC_TIMESTAMP(3) WHERE id=? AND status='PENDING'",refund));
                current.settle(refund);
                assertEquals(count,h.jdbc.queryForObject("SELECT attempts FROM refund_request WHERE id=?",Integer.class,refund));
            }
            assertEquals("REVIEW",h.jdbc.queryForObject("SELECT status FROM refund_request WHERE id=?",String.class,refund));
            refunds().retry(h.admin().userId(),refund,key(),"故障已修复，开始新的恢复代次");
            assertEquals(1L,h.jdbc.queryForObject("SELECT recovery_generation FROM refund_request WHERE id=?",Long.class,refund));
            release.countDown();worker.get(10,TimeUnit.SECONDS);
            assertEquals(0,h.jdbc.queryForObject("SELECT attempts FROM refund_request WHERE id=?",Integer.class,refund));
            assertEquals("PENDING",h.jdbc.queryForObject("SELECT status FROM refund_request WHERE id=?",String.class,refund));
            pendingOwnsInventory(paid,refund);succeed(paid,refund);
        } finally { release.countDown();executor.shutdownNow(); }
    }

    @Test void exhaustedRefundIsDurableAndOnlyAdminCanRecoverTheSameRequest() throws Exception {
        var paid=paid();long refund=accept(paid,key());var injections=new AtomicInteger();
        var failing=service(runtime(null,(stage,id)->{
            if(stage.equals("R1_BEFORE_COMMIT") && id==refund) { injections.incrementAndGet();throw new IllegalStateException("CONTROLLED_REFUND_RETRY_FAILURE"); }
        }));
        for(int attempt=1;attempt<=6;attempt++) {
            // Accelerate only this refund's retry schedule, without weakening production retry limits.
            assertEquals(1,h.jdbc.update("UPDATE refund_request SET next_attempt_at=UTC_TIMESTAMP(3) WHERE id=? AND status='PENDING'",refund));
            failing.settle(refund);
            assertEquals(attempt,h.jdbc.queryForObject("SELECT attempts FROM refund_request WHERE id=?",Integer.class,refund));
            pendingOwnsInventory(paid,refund);
        }
        assertEquals(6,injections.get());
        var review=h.call("GET","/v1/refunds/"+refund,buyer,null,null);
        assertEquals("REVIEW",review.body().path("status").asText());assertEquals("RETRY_EXHAUSTED",review.body().path("reviewReason").asText());
        assertNull(h.jdbc.queryForObject("SELECT next_attempt_at FROM refund_request WHERE id=?",java.time.LocalDateTime.class,refund));
        refunds().scanOnce();assertEquals("REVIEW",h.jdbc.queryForObject("SELECT status FROM refund_request WHERE id=?",String.class,refund));
        var listed=h.call("GET","/v1/admin/refunds?status=REVIEW",h.admin(),null,null);
        assertEquals(200,listed.status(),listed.body().toString());
        assertTrue(listed.body().findValues("refundId").stream().anyMatch(id->id.asLong()==refund),listed.body().toString());
        assertEquals(200,h.call("GET","/v1/admin/refunds/"+refund,h.admin(),null,null).status());
        String path="/v1/admin/refunds/"+refund+"/retry",requestKey=key();var reason=Map.of("reason","事务故障已修复，恢复同一笔退款");
        assertEquals(401,h.call("POST",path,null,reason,requestKey).status());assertEquals(403,h.call("POST",path,buyer,reason,requestKey).status());
        assertEquals(0,h.jdbc.queryForObject("SELECT COUNT(*) FROM refund_retry WHERE refund_id=?",Integer.class,refund));
        var retried=h.call("POST",path,h.admin(),reason,requestKey);assertTrue(retried.status()==200 || retried.status()==202,retried.body().toString());
        assertEquals(refund,retried.body().path("refundId").asLong());
        String statusUrl=retried.body().path("statusUrl").asText();assertEquals("/v1/admin/refunds/"+refund,statusUrl);
        assertEquals(200,h.call("GET",statusUrl,h.admin(),null,null).status());
        assertEquals(403,h.call("GET",statusUrl,buyer,null,null).status());
        var replay=h.call("POST",path,h.admin(),reason,requestKey);assertEquals(retried.status(),replay.status(),replay.body().toString());
        assertEquals(409,h.call("POST",path,h.admin(),Map.of("reason","同键不同恢复原因"),requestKey).status());
        assertEquals(1L,h.jdbc.queryForObject("SELECT recovery_generation FROM refund_request WHERE id=?",Long.class,refund));
        assertEquals(0,h.jdbc.queryForObject("SELECT attempts FROM refund_request WHERE id=?",Integer.class,refund));
        var audit=h.jdbc.queryForMap("SELECT operator_id,reason,generation FROM refund_retry WHERE refund_id=?",refund);
        assertEquals(h.admin().userId(),((Number)audit.get("operator_id")).longValue());assertEquals(reason.get("reason"),audit.get("reason"));assertEquals(1L,((Number)audit.get("generation")).longValue());
        succeed(paid,refund);refunds().settle(refund);
        assertEquals(1,h.jdbc.queryForObject("SELECT COUNT(*) FROM refund_request WHERE order_id=?",Integer.class,paid.order()));
        assertEquals(1,h.jdbc.queryForObject("SELECT COUNT(*) FROM refund_retry WHERE refund_id=?",Integer.class,refund));
    }

    @Test void refundReleaseRespectsExistingWaitlistAndAllocatedTicketCanBePaid() throws Exception {
        var paid=paid();var c=paid.catalogue();var input=Map.of("sessionId",Long.toString(c.sessionId()),"tierId",Long.toString(c.tierId()),"quantity",1);
        var joined=h.call("POST","/v1/waitlist-entries",waiting,input,key());assertEquals(200,joined.status(),joined.body().toString());
        long waitlist=joined.body().path("waitlistId").asLong();assertEquals("WAITING",joined.body().path("status").asText());
        long refund=accept(paid,key());succeed(paid,refund);
        var accepted=h.call("POST","/v1/booking-requests",newcomer,input,key());assertEquals(202,accepted.status(),accepted.body().toString());
        h.ctx.getBean(BookingStream.class).dispatch();h.ctx.getBean(BookingStream.class).consume();
        assertEquals("WAITLIST_REQUIRED",h.call("GET",accepted.body().path("statusUrl").asText(),newcomer,null,null).body().path("reasonCode").asText());
        h.ctx.getBean(WaitlistService.class).advance(c.tierId());
        var allocated=h.call("GET","/v1/waitlist-entries/"+waitlist,waiting,null,null);
        assertEquals("ALLOCATED",allocated.body().path("status").asText());long order=allocated.body().path("orderId").asLong();assertTrue(order>0);
        assertEquals("HOLD",h.call("GET","/v1/orders/"+order,waiting,null,null).body().path("orderStatus").asText());
        var payment=h.call("POST","/v1/orders/"+order+"/payment-attempts",waiting,Map.of(),key());assertEquals(202,payment.status(),payment.body().toString());
        h.flushWork();assertEquals("PAID",h.call("GET","/v1/orders/"+order,waiting,null,null).body().path("orderStatus").asText());
        originalPaymentUnchanged(paid);h.invariant(c);
    }

    @Test void oldRefundPaymentAndCancelRetriesCannotReleaseRepurchasedOrder() throws Exception {
        var paid=paid();String requestKey=key();long refund=accept(paid,requestKey);succeed(paid,refund);
        long replacement=h.book(buyer,paid.catalogue());assertNotEquals(paid.order(),replacement);
        var replay=h.call("POST",refundPath(paid.order()),buyer,Map.of("reason","行程变更，申请全额退款"),requestKey);
        assertEquals(202,replay.status(),replay.body().toString());assertEquals(refund,replay.body().path("refundId").asLong());
        refunds().settle(refund);h.ctx.getBean(OrderCloseService.class).expire(paid.order());
        var closed=h.call("POST","/v1/orders/"+paid.order()+"/cancel",buyer,Map.of(),null);
        assertEquals(200,closed.status(),closed.body().toString());assertEquals("REFUNDED",closed.body().path("orderStatus").asText());
        h.ctx.getBean(PaymentService.class).generate(paid.attempt());h.ctx.getBean(PaymentService.class).settle(paid.attempt());
        assertEquals("HOLD",h.call("GET","/v1/orders/"+replacement,buyer,null,null).body().path("orderStatus").asText());
        var owner=h.jdbc.queryForMap("SELECT owner_type,owner_id FROM user_session_quota WHERE user_id=? AND session_id=?",buyer.userId(),paid.catalogue().sessionId());
        assertEquals("ORDER",owner.get("owner_type"));assertEquals(replacement,((Number)owner.get("owner_id")).longValue());
        assertEquals(0,h.jdbc.queryForObject("SELECT available FROM ticket_tier WHERE id=?",Integer.class,paid.catalogue().tierId()));
        assertEquals(0,h.jdbc.queryForObject("SELECT COUNT(*) FROM inventory_ledger WHERE order_id=? AND action='RELEASE'",Integer.class,replacement));
        originalPaymentUnchanged(paid);h.invariant(paid.catalogue());
    }
}
