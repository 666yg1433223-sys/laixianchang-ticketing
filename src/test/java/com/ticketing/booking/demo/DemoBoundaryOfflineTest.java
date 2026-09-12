package com.ticketing.booking.demo;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;

class DemoBoundaryOfflineTest {
    private static final String NONCE="a".repeat(64);
    @Test void commandBindsRunNonceActionAndIdentity() {
        String id=UUID.randomUUID().toString();
        assertDoesNotThrow(()->TicketingDemoProcess.validateCommand("demo-run-0913",NONCE,"demo-run-0913",NONCE,id,"STOP"));
        assertDoesNotThrow(()->TicketingDemoProcess.validateCommand("demo-run-0913",NONCE,"demo-run-0913",NONCE,id,"SCENARIOS"));
        assertThrows(IllegalArgumentException.class,()->TicketingDemoProcess.validateCommand("demo-run-0913",NONCE,"demo-run-old",NONCE,id,"STOP"));
        assertThrows(IllegalArgumentException.class,()->TicketingDemoProcess.validateCommand("demo-run-0913",NONCE,"demo-run-0913","b".repeat(64),id,"STOP"));
        assertThrows(IllegalArgumentException.class,()->TicketingDemoProcess.validateCommand("demo-run-0913",NONCE,"demo-run-0913",NONCE,id,"SQL"));
    }
    @ParameterizedTest @ValueSource(strings={"../run-0913","C:/escape","","UPPER-123","abc","run with space"})
    void unsafeRunPathsRejectedBeforeAnyFilesystemRead(String run) {
        assertThrows(IllegalArgumentException.class,()->DemoFiles.directory(run));
    }
    @ParameterizedTest @ValueSource(strings={"../result","abc","00000000-0000-0000-0000-00000000000x",""})
    void unsafeCommandIdentityRejected(String id) {
        assertThrows(IllegalArgumentException.class,()->TicketingDemoProcess.validateCommand("demo-run-0913",NONCE,"demo-run-0913",NONCE,id,"STOP"));
    }
    private final DemoIdentityFilter.Access access=new DemoIdentityFilter.Access() {
        public boolean demoAuthorization(String header) { return "Bearer demo-current".equals(header); }
        public boolean demoBookingScope(String header,long session,long tier) { return session==10 && (tier==11||tier==12); }
    };
    @Test void oldCredentialRejectedBeforeAnyBusinessHandler() throws Exception {
        var request=new MockHttpServletRequest("POST","/v1/booking-requests");request.addHeader("Authorization","Bearer old-harness-token");
        var response=new MockHttpServletResponse();var calls=new AtomicInteger();
        new DemoIdentityFilter(access).doFilter(request,response,(q,r)->calls.incrementAndGet());
        assertEquals(401,response.getStatus());assertEquals(0,calls.get());
    }
    @ParameterizedTest @ValueSource(strings={"{\"sessionId\":\"99\",\"tierId\":\"11\",\"quantity\":1}","{\"sessionId\":\"10\",\"tierId\":\"99\",\"quantity\":1}"})
    void foreignCatalogueCannotBeBookedEvenWithValidDemoIdentity(String body) throws Exception {
        var request=request(body);var response=new MockHttpServletResponse();var calls=new AtomicInteger();
        new DemoIdentityFilter(access).doFilter(request,response,(q,r)->calls.incrementAndGet());
        assertEquals(404,response.getStatus());assertEquals(0,calls.get());
    }
    @ParameterizedTest @ValueSource(strings={"","null","{}","{\"sessionId\":10,\"tierId\":\"11\"}","{\"sessionId\":\"10\",\"sessionId\":\"99\",\"tierId\":\"11\"}","{\"sessionId\":\"10\",\"tierId\":\"11\"} {}"})
    void malformedOrAmbiguousBodyNeverReachesBusiness(String body) throws Exception {
        var response=new MockHttpServletResponse();var calls=new AtomicInteger();
        new DemoIdentityFilter(access).doFilter(request(body),response,(q,r)->calls.incrementAndGet());
        assertEquals(400,response.getStatus());assertEquals(0,calls.get());
    }
    @Test void validBodyReachesOriginalControllerUnmodified() throws Exception {
        String body="{\"sessionId\":\"10\",\"tierId\":\"11\",\"quantity\":1}";
        var calls=new AtomicInteger();new DemoIdentityFilter(access).doFilter(request(body),new MockHttpServletResponse(),(q,r)->{
            assertEquals(body,new String(q.getInputStream().readAllBytes(),StandardCharsets.UTF_8));calls.incrementAndGet();
        });assertEquals(1,calls.get());
    }
    @Test void oversizedBodyIsRejected() throws Exception {
        var response=new MockHttpServletResponse();new DemoIdentityFilter(access).doFilter(request(" ".repeat(8193)),response,(q,r)->fail("Must not reach controller"));
        assertEquals(400,response.getStatus());
    }
    private MockHttpServletRequest request(String body) {
        var request=new MockHttpServletRequest("POST","/v1/booking-requests");request.setContentType("application/json");
        request.addHeader("Authorization","Bearer demo-current");request.setContent(body.getBytes(StandardCharsets.UTF_8));return request;
    }
}
