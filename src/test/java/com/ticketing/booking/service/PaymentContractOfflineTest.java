package com.ticketing.booking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.booking.config.RuntimeServices;
import com.ticketing.booking.config.Diagnostics;
import com.ticketing.booking.model.po.PaymentAttemptPO;
import com.ticketing.booking.model.po.PaymentQueryPO;
import com.ticketing.booking.model.po.RefundQueryPO;
import com.ticketing.booking.model.po.RefundRequestPO;
import com.ticketing.booking.repository.DatabaseTimeRepository;
import com.ticketing.booking.repository.PaymentRepository;
import com.ticketing.booking.repository.RefundRepository;
import com.ticketing.booking.service.impl.PaymentServiceImpl;
import com.ticketing.booking.service.impl.RefundServiceImpl;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentContractOfflineTest {
    private static final Instant NOW=Instant.parse("2030-06-01T10:00:00.123Z");
    private static PaymentAttemptPO attempt() {
        return new PaymentAttemptPO(11,22,33,44,"payment-key",1,500,"CNY",NOW,"SUCCESS",NOW,NOW.plusSeconds(1800),
            "SUCCESS","result-123",NOW,"SIM_SUCCESS",null,"APPLIED",NOW,null,null,null,null);
    }

    @Test void typedPaymentDigestKeepsTheOriginalCanonicalBytes() throws Exception {
        String canonical="v1|11|22|33|44|500|CNY|SUCCESS|result-123|2030-06-01T10:00:00.123Z|SIM_SUCCESS";
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)),
            PaymentServiceImpl.digest(attempt()));
    }

    @Test void paymentJsonKeepsStringIdsNullsAndConditionalFlatRefundFields() throws Exception {
        var repository=mock(PaymentRepository.class);var runtime=mock(RuntimeServices.class);
        var service=new PaymentServiceImpl(repository,mock(DatabaseTimeRepository.class),runtime,mock(OrderCloseService.class),
            mock(Diagnostics.class),mock(PlatformTransactionManager.class),true);
        when(repository.findForUser(11,33,false)).thenReturn(new PaymentQueryPO(attempt(),"PAID",11L,11L,NOW,false,null,null,null));
        var json=new ObjectMapper();
        var legacy=json.readTree(json.writeValueAsString(service.query(33,11)));
        assertEquals("11",legacy.get("attemptId").textValue());
        assertEquals("22",legacy.get("orderId").textValue());
        assertEquals(500,legacy.get("amountCent").intValue());
        assertEquals("2030-06-01T10:00:00.123Z",legacy.get("createdAt").textValue());
        assertTrue(legacy.has("reviewReason"));assertTrue(legacy.get("reviewReason").isNull());
        assertTrue(legacy.get("compensationCent").isNull());
        assertFalse(legacy.has("refundLinks"));assertFalse(legacy.has("currentRefundId"));
        when(runtime.productEnabled()).thenReturn(true);
        when(repository.findForUser(11,33,true)).thenReturn(new PaymentQueryPO(attempt(),"PAID",11L,11L,NOW,true,null,null,NOW.plusSeconds(60)));
        var product=json.readTree(json.writeValueAsString(service.query(33,11)));
        assertFalse(product.has("refundLinks"));
        assertTrue(product.has("currentRefundId"));assertTrue(product.get("currentRefundId").isNull());
        assertTrue(product.get("refundedAt").isNull());
        assertEquals("2030-06-01T10:01:00.123Z",product.get("refundDeadline").textValue());
    }

    @Test void refundJsonKeepsNumericGenerationAndExplicitPendingNulls() throws Exception {
        var repository=mock(RefundRepository.class);var runtime=mock(RuntimeServices.class);
        when(runtime.productEnabled()).thenReturn(true);when(runtime.simulatorEnabled()).thenReturn(true);
        var service=new RefundServiceImpl(repository,mock(PaymentRepository.class),mock(DatabaseTimeRepository.class),runtime,
            mock(OrderCloseService.class),mock(Diagnostics.class),mock(PlatformTransactionManager.class),true);
        var refund=new RefundRequestPO(91,22,33,44,11,"refund-key",500,"CNY","changed plans","PENDING",NOW,null,NOW,0,2,null,null);
        when(repository.findForUser(33,91)).thenReturn(new RefundQueryPO(refund,"REFUNDING"));
        var json=new ObjectMapper().valueToTree(service.query(33,91));
        assertEquals("91",json.get("refundId").textValue());
        assertEquals("/v1/refunds/91",json.get("statusUrl").textValue());
        assertTrue(json.get("recoveryGeneration").isIntegralNumber());
        assertEquals(2,json.get("recoveryGeneration").longValue());
        assertTrue(json.get("completedAt").isNull());assertTrue(json.get("reviewReason").isNull());
        assertTrue(json.get("lastError").isNull());assertEquals("REFUNDING",json.get("orderStatus").textValue());
    }
}
