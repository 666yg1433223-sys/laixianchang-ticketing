package com.ticketing.booking.product;

import com.ticketing.booking.messaging.BookingStream;
import com.ticketing.booking.service.WaitlistService;
import com.ticketing.booking.recovery.WaitlistScanner;
import com.ticketing.booking.recovery.RefundScanner;

import com.ticketing.booking.config.Diagnostics;
import com.ticketing.booking.config.RuntimeServices;
import com.ticketing.booking.persistence.BookingStore;
import com.ticketing.booking.service.AllocationService;
import com.ticketing.booking.service.OrderCloseService;
import com.ticketing.booking.service.PaymentService;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;

/** Real transactions and admin HTTP recovery; fault injection exists only in test-owned Java objects. */
class ProductRecoveryIntegrationTest {
    private static ProductLiveHarness h;
    @BeforeAll static void open() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("product.live"));h=ProductLiveHarness.open();
    }
    @AfterAll static void close() { if(h!=null) { h.close(); } }
    private static String key() { return UUID.randomUUID().toString(); }
    private record BrokenRequest(ProductLiveHarness.Catalogue catalogue,ProductLiveHarness.Token user,long requestId,long eventId) { }
    private static RuntimeServices failingAt(String boundary,long target,AtomicInteger injections) {
        RuntimeServices actual=h.ctx.getBean(RuntimeServices.class);
        return (RuntimeServices)Proxy.newProxyInstance(RuntimeServices.class.getClassLoader(),new Class<?>[]{RuntimeServices.class},(proxy,method,args)-> {
            if(method.getName().equals("at")&&args!=null&&args.length==2&&boundary.equals(args[0])&&((Number)args[1]).longValue()==target) {
                injections.incrementAndGet();throw new IllegalStateException("CONTROLLED_TEST_TRANSACTION_FAILURE");
            }
            try { return method.invoke(actual,args); }
            catch(InvocationTargetException cause) { throw cause.getCause(); }
        });
    }
    private static BrokenRequest deadBookingRequest() throws Exception {
        var c=h.catalogue(1);var user=h.user();
        var accepted=h.call("POST","/v1/booking-requests",user,Map.of("sessionId",Long.toString(c.sessionId()),"tierId",Long.toString(c.tierId()),"quantity",1),key());
        assertEquals(202,accepted.status(),accepted.body().toString());long request=accepted.body().path("requestId").asLong();assertTrue(request>0);
        long event=h.jdbc.queryForObject("SELECT id FROM outbox_event WHERE event_type='BOOKING_REQUESTED' AND booking_request_id=?",Long.class,request);
        var injections=new AtomicInteger();
        var failing=new com.ticketing.booking.service.impl.AllocationServiceImpl(h.ctx.getBean(com.ticketing.booking.repository.AllocationRepository.class),h.ctx.getBean(com.ticketing.booking.repository.DatabaseTimeRepository.class),failingAt("AFTER_STOCK",request,injections),
            h.ctx.getBean(PlatformTransactionManager.class),h.ctx.getBean(Diagnostics.class));
        for(int attempt=1;attempt<=6;attempt++) {
            // Accelerate only this fixture's retry schedule; transaction clocks and deadlines remain real.
            assertEquals(1,h.jdbc.update("UPDATE outbox_event SET next_attempt_at=UTC_TIMESTAMP(3) WHERE id=? AND state='READY'",event));
            failing.process(request);
            assertEquals(attempt,h.jdbc.queryForObject("SELECT attempts FROM outbox_event WHERE id=?",Integer.class,event));
            assertEquals(1,h.jdbc.queryForObject("SELECT available FROM ticket_tier WHERE id=?",Integer.class,c.tierId()),"stock update must roll back each time");
        }
        assertEquals(6,injections.get());assertEquals("DEAD",h.jdbc.queryForObject("SELECT state FROM outbox_event WHERE id=?",String.class,event));
        assertEquals("PROCESSING_FAILED",h.call("GET","/v1/booking-requests/"+request,user,null,null).body().path("status").asText());
        assertEquals(0,h.jdbc.queryForObject("SELECT COUNT(*) FROM booking_order WHERE request_id=?",Integer.class,request));
        h.invariant(c);return new BrokenRequest(c,user,request,event);
    }
    @Test void adminRecoversFailedAllocationExactlyOnceAndOrdinaryUserCannot() throws Exception {
        var broken=deadBookingRequest();String path="/v1/admin/processing-failures/"+broken.eventId()+"/retry";
        String retryKey=key();var reason=Map.of("reason","修复受控事务故障后重新处理申请");
        assertEquals(403,h.call("POST",path,broken.user(),reason,retryKey).status());
        assertEquals(0,h.jdbc.queryForObject("SELECT COUNT(*) FROM processing_retry WHERE event_id=?",Integer.class,broken.eventId()));
        var accepted=h.call("POST",path,h.admin(),reason,retryKey);
        assertEquals(200,accepted.status(),accepted.body().toString());assertTrue(accepted.body().path("accepted").asBoolean());
        assertEquals(200,h.call("POST",path,h.admin(),reason,retryKey).status());
        assertEquals(409,h.call("POST",path,h.admin(),Map.of("reason","相同键不同参数"),retryKey).status());
        assertEquals(1L,h.jdbc.queryForObject("SELECT recovery_generation FROM outbox_event WHERE id=?",Long.class,broken.eventId()));
        assertEquals(0,h.jdbc.queryForObject("SELECT attempts FROM outbox_event WHERE id=?",Integer.class,broken.eventId()));
        var audit=h.jdbc.queryForMap("SELECT operator_id,reason FROM processing_retry WHERE event_id=?",broken.eventId());
        assertEquals(h.admin().userId(),((Number)audit.get("operator_id")).longValue());assertEquals(reason.get("reason"),audit.get("reason"));
        h.flushWork();
        var result=h.call("GET","/v1/booking-requests/"+broken.requestId(),broken.user(),null,null);
        assertEquals("SUCCEEDED",result.body().path("status").asText(),result.body().toString());
        assertEquals(1,h.jdbc.queryForObject("SELECT COUNT(*) FROM booking_order WHERE request_id=?",Integer.class,broken.requestId()));
        assertEquals("DONE",h.jdbc.queryForObject("SELECT state FROM outbox_event WHERE id=?",String.class,broken.eventId()));
        assertEquals(1,h.jdbc.queryForObject("SELECT COUNT(*) FROM processing_retry WHERE event_id=?",Integer.class,broken.eventId()));h.invariant(broken.catalogue());
    }
    @Test void adminRecoversPaymentReviewWithoutChangingTheImmutableSimulationResult() throws Exception {
        var c=h.catalogue(1);var user=h.user();long order=h.book(user,c);
        var accepted=h.call("POST","/v1/orders/"+order+"/payment-attempts",user,Map.of(),key());
        assertEquals(202,accepted.status(),accepted.body().toString());long attempt=accepted.body().path("attemptId").asLong();assertTrue(attempt>0);
        var injections=new AtomicInteger();
        var failing=new com.ticketing.booking.service.impl.PaymentServiceImpl(h.ctx.getBean(com.ticketing.booking.repository.PaymentRepository.class),h.ctx.getBean(com.ticketing.booking.repository.DatabaseTimeRepository.class),failingAt("P2_AFTER_ORDER",attempt,injections),
            h.ctx.getBean(OrderCloseService.class),h.ctx.getBean(Diagnostics.class),h.ctx.getBean(PlatformTransactionManager.class),true);
        failing.generate(attempt);
        var original=h.jdbc.queryForMap("SELECT result_id,result_status,result_at,result_digest,amount_cent FROM payment_attempt WHERE id=?",attempt);
        assertEquals("SUCCESS",original.get("result_status"));
        long event=h.jdbc.queryForObject("SELECT id FROM outbox_event WHERE event_type='PAYMENT_RESULT_RECORDED' AND payment_attempt_id=?",Long.class,attempt);
        for(int count=1;count<=6;count++) {
            assertEquals(1,h.jdbc.update("UPDATE outbox_event SET next_attempt_at=UTC_TIMESTAMP(3) WHERE id=? AND state='READY'",event));
            failing.settle(attempt);
            assertEquals(count,h.jdbc.queryForObject("SELECT attempts FROM outbox_event WHERE id=?",Integer.class,event));
            assertEquals("HOLD",h.jdbc.queryForObject("SELECT status FROM booking_order WHERE id=?",String.class,order),"PAID update must roll back");
        }
        assertEquals(6,injections.get());assertEquals("REVIEW",h.jdbc.queryForObject("SELECT settlement_status FROM payment_attempt WHERE id=?",String.class,attempt));
        assertEquals("DEAD",h.jdbc.queryForObject("SELECT state FROM outbox_event WHERE id=?",String.class,event));
        String path="/v1/admin/processing-failures/"+event+"/retry",retryKey=key();var reason=Map.of("reason","修复受控结算故障，复用原付款结果重新结算");
        assertEquals(403,h.call("POST",path,user,reason,retryKey).status());
        var recovery=h.call("POST",path,h.admin(),reason,retryKey);assertEquals(200,recovery.status(),recovery.body().toString());
        assertEquals("ACCEPTED",recovery.body().path("status").asText());String operation=recovery.body().path("operationId").asText();assertFalse(operation.isBlank());
        String statusUrl=recovery.body().path("statusUrl").asText();
        assertEquals("/v1/admin/processing-failures/operations/"+operation,statusUrl);
        assertEquals(401,h.call("GET",statusUrl,null,null,null).status());
        assertEquals(403,h.call("GET",statusUrl,user,null,null).status());
        var pending=h.call("GET",statusUrl,h.admin(),null,null);
        assertEquals(200,pending.status(),pending.body().toString());
        assertEquals("ACCEPTED",pending.body().path("status").asText());
        assertEquals(operation,h.call("POST",path,h.admin(),reason,retryKey).body().path("operationId").asText());
        assertEquals(1L,h.jdbc.queryForObject("SELECT recovery_generation FROM outbox_event WHERE id=?",Long.class,event));
        assertEquals(0,h.jdbc.queryForObject("SELECT attempts FROM outbox_event WHERE id=?",Integer.class,event));
        h.flushWork();
        var completed=h.call("GET",statusUrl,h.admin(),null,null);
        assertEquals(200,completed.status(),completed.body().toString());
        assertEquals("COMPLETED",completed.body().path("status").asText());
        assertEquals("APPLIED",completed.body().path("result").asText());
        assertEquals("PAID",h.call("GET","/v1/orders/"+order,user,null,null).body().path("orderStatus").asText());
        var result=h.call("GET","/v1/payment-attempts/"+attempt,user,null,null);
        assertEquals("APPLIED",result.body().path("settlementStatus").asText());assertEquals("SUCCESS",result.body().path("resultStatus").asText());
        var after=h.jdbc.queryForMap("SELECT result_id,result_status,result_at,result_digest,amount_cent FROM payment_attempt WHERE id=?",attempt);
        for(String field:new String[]{"result_id","result_status","result_at","amount_cent"}) { assertEquals(original.get(field),after.get(field),field); }
        assertArrayEquals((byte[])original.get("result_digest"),(byte[])after.get("result_digest"));
        var audit=h.jdbc.queryForMap("SELECT operator_id,status,result,generation,reason FROM sim_payment_operation WHERE operation_id=?",operation);
        assertEquals(h.admin().userId(),((Number)audit.get("operator_id")).longValue());assertEquals(1L,((Number)audit.get("generation")).longValue());
        assertEquals("COMPLETED",audit.get("status"));assertEquals("APPLIED",audit.get("result"));assertEquals(reason.get("reason"),audit.get("reason"));
        assertEquals(1,h.jdbc.queryForObject("SELECT COUNT(*) FROM sim_payment_operation WHERE attempt_id=?",Integer.class,attempt));
        assertEquals("DONE",h.jdbc.queryForObject("SELECT state FROM outbox_event WHERE id=?",String.class,event));h.invariant(c);
    }
    @Test void recoveryCannotReviveAnAlreadyExpiredProcessingDeadline() throws Exception {
        var broken=deadBookingRequest();
        // Controlled timestamps only on this new failed request, preserving accepted < deadline.
        assertEquals(1,h.jdbc.update("UPDATE booking_request SET accepted_at=UTC_TIMESTAMP(3)-INTERVAL 121 SECOND,process_deadline=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE id=? AND status='PROCESSING_FAILED'",broken.requestId()));
        var response=h.call("POST","/v1/admin/processing-failures/"+broken.eventId()+"/retry",h.admin(),Map.of("reason","截止后禁止复活"),key());
        assertEquals(409,response.status(),response.body().toString());assertEquals("PROCESS_DEADLINE_EXCEEDED",response.body().path("code").asText());
        assertEquals("PROCESSING_FAILED",h.jdbc.queryForObject("SELECT status FROM booking_request WHERE id=?",String.class,broken.requestId()));
        assertEquals("DEAD",h.jdbc.queryForObject("SELECT state FROM outbox_event WHERE id=?",String.class,broken.eventId()));
        assertEquals(0L,h.jdbc.queryForObject("SELECT recovery_generation FROM outbox_event WHERE id=?",Long.class,broken.eventId()));
        assertEquals(0,h.jdbc.queryForObject("SELECT COUNT(*) FROM processing_retry WHERE event_id=?",Integer.class,broken.eventId()));h.invariant(broken.catalogue());
    }
}
