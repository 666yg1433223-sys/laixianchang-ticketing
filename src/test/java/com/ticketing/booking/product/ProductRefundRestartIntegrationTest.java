package com.ticketing.booking.product;

import com.ticketing.booking.messaging.BookingStream;
import com.ticketing.booking.service.WaitlistService;
import com.ticketing.booking.recovery.WaitlistScanner;
import com.ticketing.booking.recovery.RefundScanner;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Graceful real application-context restart, not a claim of operating-system crash injection. */
class ProductRefundRestartIntegrationTest {
    @Test void durablePendingRefundSurvivesApplicationRestartAndKeepsTheOriginalLoginAndPayment() throws Exception {
        ProductLiveHarness.Token user;
        ProductLiveHarness.Catalogue catalogue;
        long order,attempt,refund;
        Map<String,Object> originalPayment;
        String refundKey=UUID.randomUUID().toString();
        try(var before=ProductLiveHarness.open()) {
            user=before.user();catalogue=before.catalogue(1);order=before.book(user,catalogue);
            var acceptedPayment=before.call("POST","/v1/orders/"+order+"/payment-attempts",user,Map.of(),UUID.randomUUID().toString());
            assertEquals(202,acceptedPayment.status(),acceptedPayment.body().toString());
            attempt=acceptedPayment.body().path("attemptId").asLong();assertTrue(attempt>0);
            before.flushWork();
            assertEquals("PAID",before.call("GET","/v1/orders/"+order,user,null,null).body().path("orderStatus").asText());
            originalPayment=payment(before,attempt);
            var acceptedRefund=before.call("POST","/v1/orders/"+order+"/refunds",user,Map.of("reason","应用重启后继续原退款"),refundKey);
            assertEquals(202,acceptedRefund.status(),acceptedRefund.body().toString());
            refund=acceptedRefund.body().path("refundId").asLong();assertTrue(refund>0);
            assertEquals("PENDING",acceptedRefund.body().path("status").asText());
            assertEquals("PENDING",before.jdbc.queryForObject("SELECT status FROM refund_request WHERE id=?",String.class,refund));
            assertEquals("REFUNDING",before.jdbc.queryForObject("SELECT status FROM booking_order WHERE id=?",String.class,order));
            assertEquals(0,before.jdbc.queryForObject("SELECT available FROM ticket_tier WHERE id=?",Integer.class,catalogue.tierId()));
            assertEquals(0,before.jdbc.queryForObject("SELECT COUNT(*) FROM inventory_ledger WHERE order_id=? AND action='RELEASE'",Integer.class,order));
            before.invariant(catalogue);
            // No scanner call after accepting the refund: it is still pending when this context closes.
        }
        try(var after=ProductLiveHarness.open()) {
            // The bearer session lives in Redis. No new login or in-memory refund object is supplied.
            var identity=after.call("GET","/v1/auth/me",user,null,null);
            assertEquals(200,identity.status(),identity.body().toString());
            assertEquals(user.userId(),identity.body().path("id").asLong());
            var recovered=after.call("GET","/v1/refunds/"+refund,user,null,null);
            assertEquals(200,recovered.status(),recovered.body().toString());
            assertEquals(refund,recovered.body().path("refundId").asLong());assertEquals(order,recovered.body().path("orderId").asLong());
            assertEquals(9900,recovered.body().path("amountCent").asInt());assertEquals("应用重启后继续原退款",recovered.body().path("reason").asText());
            // On a slow startup the real initial scanner may run before the harness destroys scheduling.
            // Either observation is valid recovery; the final state and single release remain mandatory.
            assertTrue(Set.of("PENDING","SUCCEEDED").contains(recovered.body().path("status").asText()),recovered.body().toString());
            after.flushWork();after.flushWork();
            var finished=after.call("GET","/v1/refunds/"+refund,user,null,null);
            assertEquals("SUCCEEDED",finished.body().path("status").asText());assertEquals("REFUNDED",finished.body().path("orderStatus").asText());
            assertEquals(1,after.jdbc.queryForObject("SELECT COUNT(*) FROM refund_request WHERE order_id=?",Integer.class,order));
            assertEquals(1,after.jdbc.queryForObject("SELECT COUNT(*) FROM inventory_ledger WHERE order_id=? AND action='RELEASE'",Integer.class,order));
            assertEquals(1,after.jdbc.queryForObject("SELECT available FROM ticket_tier WHERE id=?",Integer.class,catalogue.tierId()));
            var replay=after.call("POST","/v1/orders/"+order+"/refunds",user,Map.of("reason","应用重启后继续原退款"),refundKey);
            assertEquals(202,replay.status(),replay.body().toString());assertEquals(refund,replay.body().path("refundId").asLong());
            assertEquals("SUCCEEDED",replay.body().path("status").asText());
            var paymentAfter=payment(after,attempt);
            for(String field:originalPayment.keySet()) {
                if(field.equals("result_digest")) { assertArrayEquals((byte[])originalPayment.get(field),(byte[])paymentAfter.get(field)); }
                else { assertEquals(originalPayment.get(field),paymentAfter.get(field),field); }
            }
            assertEquals("SUCCESS",paymentAfter.get("result_status"));assertEquals("APPLIED",paymentAfter.get("settlement_status"));
            after.invariant(catalogue);
        }
    }
    private Map<String,Object> payment(ProductLiveHarness h,long id) {
        return h.jdbc.queryForMap("SELECT result_id,result_status,result_at,result_digest,amount_cent,currency,settlement_status,settled_at FROM payment_attempt WHERE id=?",id);
    }
}
