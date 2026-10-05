package com.ticketing.booking.product;

import com.ticketing.booking.messaging.BookingStream;
import com.ticketing.booking.service.WaitlistService;
import com.ticketing.booking.recovery.WaitlistScanner;
import com.ticketing.booking.recovery.RefundScanner;

import com.fasterxml.jackson.databind.JsonNode;
import com.ticketing.booking.catalogue.CatalogueCache;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real local-sim HTTP/MySQL/Redis tests; each test owns new catalogue and user facts. */
class ProductCatalogueIntegrationTest {
    static ProductLiveHarness h;
    @BeforeAll static void start() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("product.live"));
        h=ProductLiveHarness.open();
    }
    @AfterAll static void stop() throws Exception { if(h!=null) { h.close(); } }
    private static String key() { return "catalogue-"+UUID.randomUUID(); }
    private static ProductLiveHarness.Token admin() { return h.admin(); }
    private static JsonNode ok(ProductLiveHarness.Reply reply) {
        assertEquals(200,reply.status(),reply.body().toString());return reply.body();
    }
    private static long createDraftEvent() throws Exception {
        return ok(h.call("POST","/v1/admin/events",admin(),Map.of("title","目录集成 "+UUID.randomUUID(),"description","真实 API 创建的测试活动","venueText","隔离测试场馆"),null)).path("eventId").asLong();
    }
    private static long draftSession(long eventId) throws Exception {
        Instant now=Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return ok(h.call("POST","/v1/admin/sessions",admin(),Map.of("eventId",Long.toString(eventId),
            "startAt",now.plusSeconds(7200).toString(),"bookingOpenAt",now.minusSeconds(60).toString(),"bookingCloseAt",now.plusSeconds(3600).toString()),null)).path("sessionId").asLong();
    }
    private static long draftTier(long sessionId) throws Exception {
        return ok(h.call("POST","/v1/admin/ticket-tiers",admin(),Map.of("sessionId",Long.toString(sessionId),"name","标准票","areaLabel","无固定座位","capacity",3,"unitPriceCent",8800),null)).path("tierId").asLong();
    }
    @Test void administrativePermissionCannotBeObtainedFromClientRoleOrUserId() throws Exception {
        var user=h.user();
        assertEquals(401,h.call("GET","/v1/admin/events",null,null,null).status());
        assertEquals(403,h.call("GET","/v1/admin/events",user,null,null).status());
        assertEquals(403,h.call("POST","/v1/admin/events",user,Map.of("title","不允许创建","role","ADMIN","userId",h.admin().userId()),null).status());
        assertEquals(400,h.call("POST","/v1/admin/events",admin(),Map.of("title","拒绝可信字段","role","ADMIN"),null).status());
        assertEquals(400,h.call("GET","/v1/events?size=51",null,null,null).status());
        assertEquals(400,h.call("GET","/v1/events?page=1&page=2",null,null,null).status());
    }
    @Test void draftsStayPrivateAndPublishingInvalidatesTheirNegativeCache() throws Exception {
        long eid=createDraftEvent(),sid=draftSession(eid),tid=draftTier(sid);
        assertEquals(404,h.call("GET","/v1/events/"+eid,null,null,null).status());
        assertEquals(404,h.call("GET","/v1/sessions/"+sid+"/ticket-tiers",null,null,null).status());
        assertEquals("{\"absent\":true}",h.redis.opsForValue().get(CatalogueCache.PREFIX+"event:"+eid));
        var list=ok(h.call("GET","/v1/events?size=50",null,null,null));
        for(JsonNode item:list.path("items")) { assertNotEquals(Long.toString(eid),item.path("eventId").asText()); }
        var published=ok(h.call("POST","/v1/admin/sessions/"+sid+"/publish",admin(),Map.of(),null));
        assertEquals("OPEN",published.path("state").asText());
        assertNull(h.redis.opsForValue().get(CatalogueCache.PREFIX+"event:"+eid));
        assertNull(h.redis.opsForValue().get(CatalogueCache.PREFIX+"tiers:"+sid));
        var event=ok(h.call("GET","/v1/events/"+eid,null,null,null));
        assertEquals(Long.toString(sid),event.path("sessions").get(0).path("sessionId").asText());
        var tiers=ok(h.call("GET","/v1/sessions/"+sid+"/ticket-tiers",null,null,null));
        assertEquals(Long.toString(tid),tiers.path("items").get(0).path("tierId").asText());
        assertTrue(tiers.path("items").get(0).path("unitPriceCent").isIntegralNumber());
    }
    @Test void realColdHitAndMetadataEditRespectCacheAsideAndDurableInvalidation() throws Exception {
        var c=h.catalogue(2);var token=admin();String eventPath="/v1/events/"+c.eventId(),cacheKey=CatalogueCache.PREFIX+"event:"+c.eventId();
        assertNull(h.redis.opsForValue().get(cacheKey));
        var first=ok(h.call("GET",eventPath,null,null,null));String initial=first.path("title").asText();
        long ttl=h.redis.getExpire(cacheKey,TimeUnit.MILLISECONDS);
        assertTrue(ttl>0&&ttl<=90_000,"event cache has real TTL");
        // Only this new fixture is modified outside the API, to distinguish an actual hit from a second DB load.
        String uncached="未失效时的数据库新值 "+UUID.randomUUID();
        assertEquals(1,h.jdbc.update("UPDATE event SET title=? WHERE id=?",uncached,c.eventId()));
        assertEquals(initial,ok(h.call("GET",eventPath,null,null,null)).path("title").asText());
        String updated="管理员修改后的展示 "+UUID.randomUUID();
        ok(h.call("PATCH","/v1/admin/events/"+c.eventId(),token,Map.of("title",updated,"description","失效事实与修改一起提交"),null));
        assertNull(h.redis.opsForValue().get(cacheKey));
        assertEquals(updated,ok(h.call("GET",eventPath,null,null,null)).path("title").asText());
        var events=h.jdbc.queryForList("SELECT e.aggregate_id,e.state,e.catalogue_mutation_id FROM outbox_event e JOIN catalogue_mutation m ON m.id=e.catalogue_mutation_id WHERE m.event_id=? AND e.event_type='CATALOG_INVALIDATE' ORDER BY e.id",c.eventId());
        assertTrue(events.size()>=5);
        assertEquals(events.size(),events.stream().map(row->row.get("aggregate_id")).distinct().count());
        assertTrue(events.stream().allMatch(row->"DONE".equals(row.get("state"))&&row.get("aggregate_id").equals(row.get("catalogue_mutation_id"))));
    }
    @Test void negativeMarkerAndReferenceAvailabilityHaveDifferentRealTtls() throws Exception {
        long missing=Long.MAX_VALUE;
        assertEquals(0L,h.jdbc.queryForObject("SELECT COUNT(*) FROM event WHERE id=?",Long.class,missing));
        assertEquals(404,h.call("GET","/v1/events/"+missing,null,null,null).status());
        String missingKey=CatalogueCache.PREFIX+"event:"+missing;
        assertEquals("{\"absent\":true}",h.redis.opsForValue().get(missingKey));
        long negativeTtl=h.redis.getExpire(missingKey,TimeUnit.MILLISECONDS);
        assertTrue(negativeTtl>0&&negativeTtl<=15_000);
        assertEquals(404,h.call("GET","/v1/events/"+missing,null,null,null).status());
        var c=h.catalogue(2);
        var tiers=ok(h.call("GET","/v1/sessions/"+c.sessionId()+"/ticket-tiers",null,null,null));
        assertEquals(2,tiers.path("items").get(0).path("referenceAvailable").asInt());
        assertFalse(tiers.path("availabilityNotice").asText().isBlank());
        long tierTtl=h.redis.getExpire(CatalogueCache.PREFIX+"tiers:"+c.sessionId(),TimeUnit.MILLISECONDS);
        assertTrue(tierTtl>0&&tierTtl<=5_000);
        assertEquals(200,h.call("GET","/v1/events/"+c.eventId(),null,null,null).status(),"an unrelated negative marker cannot hide a real event");
    }
    @Test void publishedPriceCapacityAndTimesStayFrozenAcrossPauseResume() throws Exception {
        var c=h.catalogue(2);var token=admin();
        assertEquals(409,h.call("PATCH","/v1/admin/ticket-tiers/"+c.tierId(),token,Map.of("unitPriceCent",123),null).status());
        assertEquals(409,h.call("PATCH","/v1/admin/ticket-tiers/"+c.tierId(),token,Map.of("capacity",50),null).status());
        assertEquals(409,h.call("PATCH","/v1/admin/sessions/"+c.sessionId(),token,Map.of("startAt",Instant.now().plusSeconds(86400).truncatedTo(ChronoUnit.MILLIS).toString()),null).status());
        ok(h.call("POST","/v1/admin/sessions/"+c.sessionId()+"/pause",token,Map.of(),null));
        assertEquals("PAUSED",ok(h.call("GET","/v1/sessions/"+c.sessionId()+"/ticket-tiers",null,null,null)).path("state").asText());
        assertEquals(409,h.call("PATCH","/v1/admin/ticket-tiers/"+c.tierId(),token,Map.of("capacity",10),null).status());
        assertEquals(409,h.call("POST","/v1/admin/ticket-tiers",token,Map.of("sessionId",Long.toString(c.sessionId()),"name","追加票","capacity",1,"unitPriceCent",100),null).status());
        ok(h.call("PATCH","/v1/admin/ticket-tiers/"+c.tierId(),token,Map.of("name","允许修改的票档说明","areaLabel","二层看台"),null));
        ok(h.call("POST","/v1/admin/sessions/"+c.sessionId()+"/resume",token,Map.of(),null));
        var current=ok(h.call("GET","/v1/sessions/"+c.sessionId()+"/ticket-tiers",null,null,null));
        assertEquals("OPEN",current.path("state").asText());assertEquals("二层看台",current.path("items").get(0).path("areaLabel").asText());
        assertEquals(2,current.path("items").get(0).path("capacity").asInt());
    }
    @Test void listingOnlyReturnsTheAuthenticatedUsersOrdersWithBoundedPagination() throws Exception {
        var c=h.catalogue(3);var alice=h.user();var bob=h.user();
        var accepted=h.call("POST","/v1/booking-requests",alice,Map.of("sessionId",Long.toString(c.sessionId()),"tierId",Long.toString(c.tierId()),"quantity",1),key());
        assertEquals(202,accepted.status(),accepted.body().toString());
        h.flushWork();
        var request=ok(h.call("GET","/v1/booking-requests/"+accepted.body().path("requestId").asText(),alice,null,null));
        assertEquals("SUCCEEDED",request.path("status").asText());
        String order=request.path("order").path("orderId").asText();assertFalse(order.isBlank());
        var own=ok(h.call("GET","/v1/orders?page=1&size=1",alice,null,null));
        assertEquals(1,own.path("items").size());assertEquals(order,own.path("items").get(0).path("orderId").asText());
        assertEquals(Long.toString(c.eventId()),own.path("items").get(0).path("eventId").asText());
        assertFalse(own.path("items").get(0).path("eventTitle").asText().isBlank());
        assertEquals(0,ok(h.call("GET","/v1/orders",bob,null,null)).path("items").size());
        assertEquals(404,h.call("GET","/v1/orders/"+order,bob,null,null).status());
        assertEquals(400,h.call("GET","/v1/orders?userId="+alice.userId(),bob,null,null).status());
    }
}
