package com.ticketing.booking.product;

import com.ticketing.booking.service.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductFlowIntegrationTest {
    private ProductLiveHarness h;
    @BeforeAll void open() throws Exception { h=ProductLiveHarness.open(); }
    @AfterAll void close() { if(h!=null) { h.close(); } }
    @Test void ordinaryLoginOwnershipPaymentAndLogout() throws Exception {
        var user=h.user();var other=h.user();var c=h.catalogue(2);long order=h.book(user,c);
        assertEquals(404,h.call("GET","/v1/orders/"+order,other,null,null).status());
        assertEquals(403,h.call("GET","/v1/admin/events",user,null,null).status());
        String key=UUID.randomUUID().toString();
        var pay=h.call("POST","/v1/orders/"+order+"/payment-attempts",user,Map.of(),key);
        assertEquals(202,pay.status(),pay.body().toString());
        assertEquals(pay.body().path("attemptId"),h.call("POST","/v1/orders/"+order+"/payment-attempts",user,Map.of(),key).body().path("attemptId"));
        h.flushWork();
        var result=h.call("GET","/v1/orders/"+order,user,null,null);
        assertEquals("PAID",result.body().path("orderStatus").asText());
        assertEquals(409,h.call("POST","/v1/orders/"+order+"/cancel",user,Map.of(),null).status());
        assertEquals(200,h.call("POST","/v1/auth/logout",user,Map.of(),null).status());
        assertEquals(401,h.call("GET","/v1/orders",user,null,null).status());h.invariant(c);
    }
    @Test void waitingHasPriorityAndAllocationReusesPayment() throws Exception {
        var buyer=h.user();var waiting=h.user();var newcomer=h.user();var c=h.catalogue(1);long order=h.book(buyer,c);
        String key=UUID.randomUUID().toString();var input=Map.of("sessionId",Long.toString(c.sessionId()),"tierId",Long.toString(c.tierId()),"quantity",1);
        var joined=h.call("POST","/v1/waitlist-entries",waiting,input,key);assertEquals(200,joined.status(),joined.body().toString());
        long w=joined.body().path("waitlistId").asLong();assertEquals("WAITING",joined.body().path("status").asText());
        assertEquals(w,h.call("POST","/v1/waitlist-entries",waiting,input,key).body().path("waitlistId").asLong());
        assertEquals(404,h.call("GET","/v1/waitlist-entries/"+w,newcomer,null,null).status());
        assertEquals(200,h.call("POST","/v1/orders/"+order+"/cancel",buyer,Map.of(),null).status());
        var accept=h.call("POST","/v1/booking-requests",newcomer,input,UUID.randomUUID().toString());assertEquals(202,accept.status());
        var stream=h.ctx.getBean(BookingStream.class);stream.dispatch();stream.consume();
        assertEquals("WAITLIST_REQUIRED",h.call("GET",accept.body().path("statusUrl").asText(),newcomer,null,null).body().path("reasonCode").asText());
        h.ctx.getBean(WaitlistService.class).advance(c.tierId());
        var allocated=h.call("GET","/v1/waitlist-entries/"+w,waiting,null,null);assertEquals("ALLOCATED",allocated.body().path("status").asText());
        long next=allocated.body().path("orderId").asLong();
        assertEquals(409,h.call("POST","/v1/waitlist-entries/"+w+"/cancel",waiting,Map.of(),null).status());
        var pay=h.call("POST","/v1/orders/"+next+"/payment-attempts",waiting,Map.of(),UUID.randomUUID().toString());assertEquals(202,pay.status(),pay.body().toString());
        h.flushWork();assertEquals("PAID",h.call("GET","/v1/orders/"+next,waiting,null,null).body().path("orderStatus").asText());
        h.invariant(c);
    }
    @Test void cancelWaitlistAndFifoDoNotRestoreOldPosition() throws Exception {
        var buyer=h.user();var first=h.user();var second=h.user();var c=h.catalogue(1);long original=h.book(buyer,c);
        var input=Map.of("sessionId",Long.toString(c.sessionId()),"tierId",Long.toString(c.tierId()),"quantity",1);
        long a=h.call("POST","/v1/waitlist-entries",first,input,UUID.randomUUID().toString()).body().path("waitlistId").asLong();
        long b=h.call("POST","/v1/waitlist-entries",second,input,UUID.randomUUID().toString()).body().path("waitlistId").asLong();
        assertTrue(a>0 && b>a);
        h.call("POST","/v1/orders/"+original+"/cancel",buyer,Map.of(),null);
        h.ctx.getBean(WaitlistService.class).advance(c.tierId());
        var ar=h.call("GET","/v1/waitlist-entries/"+a,first,null,null);
        assertEquals("ALLOCATED",ar.body().path("status").asText());
        long next=ar.body().path("orderId").asLong();
        assertEquals("WAITING",h.call("GET","/v1/waitlist-entries/"+b,second,null,null).body().path("status").asText());
        assertEquals(200,h.call("POST","/v1/orders/"+next+"/cancel",first,Map.of(),null).status());
        h.ctx.getBean(WaitlistService.class).advance(c.tierId());
        assertEquals("ALLOCATED",h.call("GET","/v1/waitlist-entries/"+b,second,null,null).body().path("status").asText());
        assertEquals("ALLOCATED",h.call("GET","/v1/waitlist-entries/"+a,first,null,null).body().path("status").asText());h.invariant(c);
    }
    @Test void concurrentConsumersAndDuplicateDeliveryHaveOneEffect() throws Exception {
        var user=h.user();var c=h.catalogue(1);
        var accepted=h.call("POST","/v1/booking-requests",user,Map.of("sessionId",Long.toString(c.sessionId()),"tierId",Long.toString(c.tierId()),"quantity",1),UUID.randomUUID().toString());
        long id=accepted.body().path("requestId").asLong();assertTrue(id>0);
        var executor=java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var tasks=new ArrayList<java.util.concurrent.Callable<Void>>();
            for(int i=0;i<8;i++) { tasks.add(()->{h.ctx.getBean(AllocationService.class).process(id);return null;}); }
            for(var task:executor.invokeAll(tasks)) { task.get(); }
        } finally { executor.shutdownNow(); }
        h.ctx.getBean(BookingStream.class).dispatch();h.ctx.getBean(BookingStream.class).consume();
        assertEquals(1,h.jdbc.queryForObject("SELECT COUNT(*) FROM booking_order WHERE request_id=?",Integer.class,id));
        assertEquals("SUCCEEDED",h.call("GET","/v1/booking-requests/"+id,user,null,null).body().path("status").asText());h.invariant(c);
    }
    @Test void malformedStreamMessageIsDurablyQuarantinedBeforeAck() {
        var stream=h.ctx.getBean(BookingStream.class);stream.ensureGroup();
        var id=h.redis.opsForStream().add(BookingStream.KEY,Map.of("eventType","not-a-booking"));
        stream.consume();
        assertEquals(1,h.jdbc.queryForObject("SELECT COUNT(*) FROM invalid_stream_message WHERE message_id=?",Integer.class,id.getValue()));
        assertEquals(0,h.redis.opsForStream().pending(BookingStream.KEY,BookingStream.GROUP,org.springframework.data.domain.Range.closed(id.getValue(),id.getValue()),1).size());
    }
    @Test void anotherConsumerClaimsAnIdlePendingMessage() throws Exception {
        var user=h.user();var c=h.catalogue(1);var stream=h.ctx.getBean(BookingStream.class);stream.ensureGroup();
        var accepted=h.call("POST","/v1/booking-requests",user,Map.of("sessionId",Long.toString(c.sessionId()),"tierId",Long.toString(c.tierId()),"quantity",1),UUID.randomUUID().toString());
        long request=accepted.body().path("requestId").asLong();assertTrue(request>0);
        stream.dispatch();
        var messages=h.redis.<String,String>opsForStream().read(org.springframework.data.redis.connection.stream.Consumer.from(BookingStream.GROUP,"crashed-test-consumer"),org.springframework.data.redis.connection.stream.StreamReadOptions.empty().count(100),org.springframework.data.redis.connection.stream.StreamOffset.create(BookingStream.KEY,org.springframework.data.redis.connection.stream.ReadOffset.lastConsumed()));
        var record=messages.stream().filter(m->Long.toString(request).equals(m.getValue().get("requestId"))).findFirst().orElseThrow();
        // Controlled test age for this single message, not a claim that a process really crashed.
        h.redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) connection -> connection.execute("XCLAIM",
            b(BookingStream.KEY),b(BookingStream.GROUP),b("crashed-test-consumer"),b("0"),b(record.getId().getValue()),b("IDLE"),b("65000"),b("JUSTID")));
        stream.reclaim();
        assertEquals("SUCCEEDED",h.call("GET","/v1/booking-requests/"+request,user,null,null).body().path("status").asText(),stream.lastClaimFailure());
        assertEquals(0,h.redis.opsForStream().pending(BookingStream.KEY,BookingStream.GROUP,org.springframework.data.domain.Range.closed(record.getId().getValue(),record.getId().getValue()),1).size());h.invariant(c);
    }
    @Test void publishedButUndeliveredEventCanBeRepublishedFromDatabase() throws Exception {
        var user=h.user();var c=h.catalogue(1);var stream=h.ctx.getBean(BookingStream.class);
        var accepted=h.call("POST","/v1/booking-requests",user,Map.of("sessionId",Long.toString(c.sessionId()),"tierId",Long.toString(c.tierId()),"quantity",1),UUID.randomUUID().toString());
        long request=accepted.body().path("requestId").asLong();assertTrue(request>0);
        // Simulate publication acknowledged in MySQL but no recoverable Redis delivery remains.
        h.jdbc.update("UPDATE outbox_event SET state='PUBLISHED',published_at=UTC_TIMESTAMP(3)-INTERVAL 31 SECOND WHERE booking_request_id=?",request);
        stream.dispatch();stream.consume();
        assertEquals("SUCCEEDED",h.call("GET","/v1/booking-requests/"+request,user,null,null).body().path("status").asText());h.invariant(c);
    }
    @Test void allocatedWaitlistExpiryReleasesExactlyOnceWithoutRejoining() throws Exception {
        var buyer=h.user();var waiting=h.user();var c=h.catalogue(1);long initial=h.book(buyer,c);
        var joined=h.call("POST","/v1/waitlist-entries",waiting,Map.of("sessionId",Long.toString(c.sessionId()),"tierId",Long.toString(c.tierId()),"quantity",1),UUID.randomUUID().toString());
        long w=joined.body().path("waitlistId").asLong();assertTrue(w>0);
        h.call("POST","/v1/orders/"+initial+"/cancel",buyer,Map.of(),null);h.ctx.getBean(WaitlistService.class).advance(c.tierId());
        long order=h.call("GET","/v1/waitlist-entries/"+w,waiting,null,null).body().path("orderId").asLong();assertTrue(order>0);
        // Only this test's new HOLD receives controlled timestamps; never a public time override.
        h.jdbc.update("UPDATE booking_order SET hold_created_at=UTC_TIMESTAMP(3)-INTERVAL 600 SECOND,pay_expires_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE id=? AND status='HOLD'",order);
        h.ctx.getBean(OrderCloseService.class).expire(order);h.ctx.getBean(OrderCloseService.class).expire(order);
        assertEquals("EXPIRED",h.call("GET","/v1/orders/"+order,waiting,null,null).body().path("orderStatus").asText());
        assertEquals("ALLOCATED",h.call("GET","/v1/waitlist-entries/"+w,waiting,null,null).body().path("status").asText());h.invariant(c);
    }
    private static byte[] b(String value) { return value.getBytes(java.nio.charset.StandardCharsets.UTF_8); }
}
