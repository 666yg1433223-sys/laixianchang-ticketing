package com.ticketing.booking.demo;

import com.ticketing.booking.config.Bootstrap;
import com.ticketing.booking.fixture.BookingHarness;
import com.ticketing.booking.service.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Requires the original MySQL/Redis IT environment. Never selected by the Offline entry point. */
class DemoIsolationIntegrationTest {
    @Test void realWorkersAndHttpCannotTouchOtherRunSentinels() throws Exception {
        String base=System.getProperty("biz.runId","");
        if(!base.matches("[a-z0-9-]{8,20}")) throw new IllegalArgumentException("Provide a fresh 8..20 character biz.runId");
        String sentinelRun=base+"-old",demoRun=base+"-new";
        var requests=new LinkedHashMap<String,Long>();var attempts=new LinkedHashMap<String,Long>();
        BookingHarness.Fixture foreign;
        System.setProperty("biz.evidenceTask","BIZ-04-I-001");
        try(var old=new BookingHarness(sentinelRun,false)) {
            var now=old.jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)",java.sql.Timestamp.class).toInstant();
            foreign=old.fixtureAt("foreign-accepted",2,now);old.runtime.direct=false;
            var accepted=old.post(foreign,"foreign-request-key");assertEquals(202,accepted.status());requests.put("accepted",accepted.id());
            for(String stage:List.of("pending","ready","hold")) {
                var f=old.fixtureAt("foreign-"+stage,2,now);old.runtime.direct=false;
                var request=old.post(f,"foreign-"+stage+"-key");assertEquals(202,request.status());
                old.app.getBean(AllocationService.class).process(request.id());
                long order=Long.parseLong(old.query(request.id()).body().path("order").path("orderId").asText());
                requests.put(stage,request.id());
                if(!stage.equals("hold")) {
                    old.runtime.plan=new com.ticketing.booking.config.RuntimeServices.SimulationPlan("SUCCESS",0);
                    var payment=old.request("POST","/v1/orders/"+order+"/payment-attempts","{}","foreign-pay-"+stage,false,true);
                    assertEquals(202,payment.status());long attempt=Long.parseLong(payment.body().path("attemptId").asText());attempts.put(stage,attempt);
                    if(stage.equals("ready")) old.app.getBean(PaymentService.class).generate(attempt);
                }
            }
            old.cleanOnClose=false;
        }
        DemoFiles.createDirectory(Bootstrap.ROOT.resolve(".local/demo"));
        Path directory=Bootstrap.ROOT.resolve(".local/demo").resolve(demoRun);
        assertFalse(Files.exists(directory),"Do not reuse an old demo run");DemoFiles.createDirectory(directory);
        try(var demo=new DemoSession(demoRun)) {
            var own=demo.fixture("owned",2);
            String forbidden=demo.h.json.writeValueAsString(Map.of("sessionId",Long.toString(foreign.session()),"tierId",Long.toString(foreign.tier()),"quantity",1));
            assertEquals(404,demo.request(own,"POST","/v1/booking-requests",forbidden,"foreign-blocked-key",false,true).status());
            var accepted=demo.request(own,"POST","/v1/booking-requests",demo.h.input(own),"owned-booking-key",false,true);
            assertEquals(202,accepted.status());
            // A later reset cannot accidentally re-enable any of the original global schedulers.
            demo.h.runtime.reset();assertFalse(demo.h.runtime.directEnabled());assertFalse(demo.h.runtime.recoveryEnabled());
            for(int i=0;i<3;i++) {demo.driveOnce();Thread.sleep(500);}
            var result=demo.request(own,"GET","/v1/booking-requests/"+accepted.id(),null,null,false,true);
            assertEquals("HOLD",result.body().path("order").path("orderStatus").asText());
            assertEquals("ACCEPTED",demo.h.jdbc.queryForObject("SELECT status FROM booking_request WHERE id=?",String.class,requests.get("accepted")));
            assertEquals("PENDING",demo.h.jdbc.queryForObject("SELECT result_status FROM payment_attempt WHERE id=?",String.class,attempts.get("pending")));
            assertEquals("READY",demo.h.jdbc.queryForObject("SELECT state FROM outbox_event WHERE payment_attempt_id=?",String.class,attempts.get("ready")));
            demo.setTime(demo.now().plusSeconds(700));demo.driveOnce();
            for(String stage:List.of("pending","ready","hold"))
                assertEquals("HOLD",demo.h.jdbc.queryForObject("SELECT status FROM booking_order WHERE request_id=?",String.class,requests.get(stage)));
            demo.verifyPriorFactsUnchanged();
            demo.save("cross-run-isolation.json",Map.of("passed",true,"realHttpForeignCatalogueRejected",true,"sentinelRun",sentinelRun,"sentinelRequests",requests,"sentinelAttempts",attempts,"fixturesRetained",true));
        }
    }
}
