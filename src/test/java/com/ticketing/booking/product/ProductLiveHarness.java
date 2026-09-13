package com.ticketing.booking.product;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.*;
import com.ticketing.booking.application.BookingApplication;
import com.ticketing.booking.auth.AuthService;
import com.ticketing.booking.catalogue.CatalogueInvalidator;
import com.ticketing.booking.recovery.PaymentRecoveryScanner;
import com.ticketing.booking.recovery.OrderExpiryScanner;
import com.ticketing.booking.config.Bootstrap;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in, real localhost HTTP/MySQL/Redis; no fixture endpoints in production. */
public final class ProductLiveHarness implements AutoCloseable {
    public final ConfigurableApplicationContext ctx;
    public final JdbcTemplate jdbc;
    public final StringRedisTemplate redis;
    private final Token admin;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json=new ObjectMapper();
    public record Token(String token,long userId) { }
    public record Reply(int status,JsonNode body,HttpHeaders headers) {
        public Reply(int status,JsonNode body) { this(status,body,HttpHeaders.of(Map.of(),(name,value)->true)); }
        public Optional<String> header(String name) { return headers.firstValue(name); }
    }
    public record Catalogue(long eventId,long sessionId,long tierId) { }
    private ProductLiveHarness() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("product.live"),"requires -Dproduct.live=true");
        String adminPhone=phone();
        ctx=new SpringApplication(BookingApplication.class).run("--spring.profiles.active=local-sim","--biz.local.admin-phone="+adminPhone);
        // Tests drive the same scanners deterministically; production has no disable/test route.
        ctx.getBean(ScheduledAnnotationBeanPostProcessor.class).destroy();
        jdbc=ctx.getBean(JdbcTemplate.class);redis=ctx.getBean(StringRedisTemplate.class);
        assertEquals("ticketing_biz01",jdbc.queryForObject("SELECT DATABASE()",String.class));
        assertEquals(7,jdbc.queryForObject("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1",Integer.class));
        admin=login(adminPhone);
        assertEquals("ADMIN",call("GET","/v1/auth/me",admin,null,null).body().path("role").asText());
    }
    public static ProductLiveHarness open() throws Exception { return new ProductLiveHarness(); }
    public Token admin() { return admin; }
    public Token user() throws Exception { return login(phone()); }
    public Reply call(String method,String path,Token token,Map<String,?> body,String key) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:18082"+path)).timeout(Duration.ofSeconds(15));
        if(token!=null) { builder.header("Authorization","Bearer "+token.token()); }
        if(key!=null) { builder.header("Idempotency-Key",key); }
        if(body!=null) { builder.header("Content-Type","application/json"); }
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var reply=http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
        return new Reply(reply.statusCode(),reply.body().isBlank()?json.nullNode():json.readTree(reply.body()),reply.headers());
    }
    private Token login(String phone) throws Exception {
        Logger log=(Logger)LoggerFactory.getLogger(AuthService.class);
        var messages=new ListAppender<ILoggingEvent>();messages.start();boolean additive=log.isAdditive();
        log.setAdditive(false);log.addAppender(messages);
        try {
            // Real request includes ordinary per-IP and per-phone send protection.
            var sent=sendLoginCode(phone);
            assertEquals(200,sent.status(),sent.body().toString());assertFalse(sent.body().has("code"));
            String sms=messages.list.get(messages.list.size()-1).getFormattedMessage();
            var match=java.util.regex.Pattern.compile("code=([0-9]{6})").matcher(sms);assertTrue(match.find());
            var login=call("POST","/v1/auth/login",null,Map.of("phone",phone,"code",match.group(1)),null);
            assertEquals(200,login.status(),login.body().path("code").asText());
            return new Token(login.body().path("token").asText(),login.body().path("user").path("id").asLong());
        } finally { log.detachAppender(messages);log.setAdditive(additive);messages.stop();messages.list.clear(); }
    }
    private Reply sendLoginCode(String phone) throws Exception {
        // Keep production limits intact: at most three HTTP attempts and 120 seconds total waiting.
        // Each request already has a 15-second timeout; every individual wait is at most 60 seconds.
        long waitedSeconds=0;
        for(int attempt=1;attempt<=3;attempt++) {
            var sent=call("POST","/v1/auth/code",null,Map.of("phone",phone),null);
            String code=sent.body().path("code").asText();
            boolean sendLimited=sent.status()==429 && ("CODE_SEND_TOO_FREQUENT".equals(code)
                || "CODE_SEND_RATE_LIMITED".equals(code));
            if(!sendLimited) {
                assertEquals(200,sent.status(),sent.body().toString());
                return sent;
            }
            assertTrue(attempt<3,"Verification-code send remained rate limited after three attempts: "+code);
            String retryAfter=sent.header("Retry-After").orElseThrow(
                ()->new AssertionError("Verification-code rate limit omitted Retry-After"));
            assertTrue(retryAfter.matches("[0-9]{1,3}"),"Expected Retry-After delay in seconds: "+retryAfter);
            long seconds=Long.parseLong(retryAfter);
            assertTrue(seconds>=1 && seconds<=60,"Retry-After exceeds the bounded test wait: "+seconds);
            waitedSeconds+=seconds;
            assertTrue(waitedSeconds<=120,"Verification-code send exceeded its 120-second wait budget");
            Thread.sleep(Duration.ofSeconds(seconds).toMillis());
        }
        throw new AssertionError("Verification-code send exhausted its attempt budget");
    }
    public Catalogue catalogue(int capacity) throws Exception {
        var e=call("POST","/v1/admin/events",admin,Map.of("title","集成验证 "+UUID.randomUUID().toString().substring(0,8),"description","本地验证，无真实资金","venueText","测试会场"),null);
        assertTrue(e.status()==200 || e.status()==201,e.body().toString());long event=e.body().path("eventId").asLong();
        var now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        var s=call("POST","/v1/admin/sessions",admin,Map.of("eventId",Long.toString(event),"startAt",now.plusSeconds(7200).toString(),"bookingOpenAt",now.minusSeconds(60).toString(),"bookingCloseAt",now.plusSeconds(3600).toString()),null);
        assertTrue(s.status()==200 || s.status()==201,s.body().toString());long session=s.body().path("sessionId").asLong();
        var t=call("POST","/v1/admin/ticket-tiers",admin,Map.of("sessionId",Long.toString(session),"name","普通票","capacity",capacity,"unitPriceCent",9900),null);
        assertTrue(t.status()==200 || t.status()==201,t.body().toString());long tier=t.body().path("tierId").asLong();
        var publish=call("POST","/v1/admin/sessions/"+session+"/publish",admin,Map.of(),null);
        assertEquals(200,publish.status(),publish.body().toString());return new Catalogue(event,session,tier);
    }
    public void flushWork() {
        var stream=ctx.getBean(BookingStream.class);stream.dispatch();stream.consume();
        ctx.getBean(PaymentRecoveryScanner.class).generateOnce();ctx.getBean(PaymentRecoveryScanner.class).settleOnce();
        ctx.getBean(RefundScanner.class).scan();ctx.getBean(OrderExpiryScanner.class).scanOnce();ctx.getBean(WaitlistScanner.class).scan();ctx.getBean(CatalogueInvalidator.class).tick();
    }
    public long book(Token user,Catalogue c) throws Exception {
        var accepted=call("POST","/v1/booking-requests",user,Map.of("sessionId",Long.toString(c.sessionId()),"tierId",Long.toString(c.tierId()),"quantity",1),UUID.randomUUID().toString());
        assertEquals(202,accepted.status(),accepted.body().toString());
        flushWork();var r=call("GET",accepted.body().path("statusUrl").asText(),user,null,null);
        assertEquals("SUCCEEDED",r.body().path("status").asText(),r.body().toString());return r.body().path("order").path("orderId").asLong();
    }
    public void invariant(Catalogue c) {
        long allocated=jdbc.queryForObject("SELECT COUNT(*) FROM booking_order WHERE tier_id=? AND status IN ('HOLD','PAID','REFUNDING')",Long.class,c.tierId());
        var t=jdbc.queryForMap("SELECT capacity,available FROM ticket_tier WHERE id=?",c.tierId());
        assertEquals(((Number)t.get("capacity")).longValue()-((Number)t.get("available")).longValue(),allocated);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM booking_order o LEFT JOIN user_session_quota q ON q.user_id=o.user_id AND q.session_id=o.session_id WHERE o.tier_id=? AND o.status IN ('HOLD','PAID','REFUNDING') AND (q.owner_type<>'ORDER' OR q.owner_id<>o.id OR q.owner_id IS NULL)",Integer.class,c.tierId()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM booking_order o WHERE o.tier_id=? AND ((SELECT COUNT(*) FROM inventory_ledger l WHERE l.order_id=o.id AND l.action='RESERVE')<>1 OR (SELECT COUNT(*) FROM inventory_ledger l WHERE l.order_id=o.id AND l.action='RELEASE')<>IF(o.status IN ('CANCELLED','EXPIRED','REFUNDED'),1,0))",Integer.class,c.tierId()));
    }
    private static String phone() { return "139"+String.format(Locale.ROOT,"%08d",java.util.concurrent.ThreadLocalRandom.current().nextInt(100_000_000)); }
    public void close() { ctx.close(); }
}
