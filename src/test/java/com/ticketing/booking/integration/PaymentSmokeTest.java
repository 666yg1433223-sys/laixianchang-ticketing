package com.ticketing.booking.integration;

import com.ticketing.booking.fixture.BookingHarness;
import com.ticketing.booking.service.PaymentService;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaymentSmokeTest {
    @Test void realHttpAcceptanceResultAndSettlement() throws Exception {
        try(var h=new BookingHarness(System.getProperty("biz.runId")+"-pay-smoke")) {
            var f=h.fixture("P01",1);
            var accepted=h.post(f,"smoke-booking-key");assertEquals(202,accepted.status());
            long order=Long.parseLong(h.query(accepted.id()).body().path("order").path("orderId").asText());
            var p=h.request("POST","/v1/orders/"+order+"/payment-attempts","{}","smoke-payment-key",false,true);
            assertEquals(202,p.status(),p.body().toString());long attempt=Long.parseLong(p.body().path("attemptId").asText());
            var pending=h.request("GET","/v1/payment-attempts/"+attempt,null,null,false,true);
            assertEquals("PENDING",pending.body().path("resultStatus").asText());assertEquals("UNRESOLVED",pending.body().path("settlementStatus").asText());
            h.save("P01-after-P0.json",h.snapshot(f));
            h.runtime.fixedTime=BookingHarness.BASE.plusSeconds(1);
            var payment=h.app.getBean(PaymentService.class);payment.generate(attempt);
            h.save("P01-after-P1.json",h.snapshot(f));
            assertEquals("HOLD",h.jdbc.queryForObject("SELECT status FROM booking_order WHERE id=?",String.class,order));
            payment.settle(attempt);
            var result=h.request("GET","/v1/payment-attempts/"+attempt,null,null,false,true);
            assertEquals("SUCCESS",result.body().path("resultStatus").asText());assertEquals("APPLIED",result.body().path("settlementStatus").asText());
            assertEquals("PAID",result.body().path("orderStatus").asText());assertTrue(result.body().path("simulated").asBoolean());
            assertEquals(9900,result.body().path("amountCent").asInt());assertEquals(Long.toString(attempt),result.body().path("paidAttemptId").asText());
            var request=h.query(accepted.id());assertEquals("SUCCEEDED",request.body().path("status").asText());
            assertEquals("PAID",request.body().path("order").path("orderStatus").asText());
            var orderResult=h.request("GET","/v1/orders/"+order,null,null,false,true);
            var contract=new OrderContractTest();contract.spec=h.json.readTree(Files.readString(com.ticketing.booking.config.Bootstrap.ROOT.resolve("docs/biz03-openapi.json")));
            contract.response("/v1/orders/{id}/payment-attempts","post",p);
            contract.response("/v1/payment-attempts/{id}","get",pending);contract.response("/v1/payment-attempts/{id}","get",result);
            contract.response("/v1/orders/{id}","get",orderResult);contract.response("/v1/booking-requests/{id}","get",request);
            h.assertCounts(f,1,1,1,0);h.pass(f,"Real HTTP P0 and own query; independently persisted P1 then atomic P2 PAID/APPLIED; no second stock debit; current static schema");
            h.cleanOnClose=true;
        }
    }
}
