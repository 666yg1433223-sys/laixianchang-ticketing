package com.ticketing.booking.integration;

import com.ticketing.booking.fixture.BookingHarness;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SmokeTest {
    @Test void firstRealHttpHold() throws Exception {
        try(var h=new BookingHarness(System.getProperty("biz.runId","biz01-default")+"-smoke")){
            var f=h.fixture("C01",1);
            var response=h.post(f,"smoke-first-0001");
            assertEquals(202,response.status());
            var result=h.query(response.id());
            assertEquals(200,result.status());
            assertEquals("SUCCEEDED",result.body().path("status").asText());
            assertEquals("HOLD",result.body().path("order").path("orderStatus").asText());
            assertEquals(9900,result.body().path("order").path("payableCent").asInt());
            h.assertCounts(f,1,1,1,0);
            h.pass(f,"First real HTTP 202 -> GET HOLD; SQL R/O/Q/L/E and stock asserted.");
            h.cleanOnClose=true;
        }
    }
}
