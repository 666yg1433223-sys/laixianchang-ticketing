package com.ticketing.booking.service;

import com.ticketing.booking.config.Diagnostics;
import com.ticketing.booking.config.RuntimeServices;
import com.ticketing.booking.model.po.PaymentAttemptPO;
import com.ticketing.booking.model.po.PaymentLedgerPO;
import com.ticketing.booking.model.po.RefundGenerationPO;
import com.ticketing.booking.model.po.RefundRequestPO;
import com.ticketing.booking.repository.DatabaseTimeRepository;
import com.ticketing.booking.repository.PaymentRepository;
import com.ticketing.booking.repository.RefundRepository;
import com.ticketing.booking.service.impl.PaymentServiceImpl;
import com.ticketing.booking.service.impl.RefundServiceImpl;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.mockito.Mockito.*;

class RefundSettlementOfflineTest {
    private static final Instant NOW=Instant.parse("2030-06-01T10:00:00Z");
    private RefundRepository refunds;
    private PaymentRepository payments;
    private RuntimeServices runtime;
    private DatabaseTimeRepository clock;
    private OrderCloseService close;
    private PlatformTransactionManager transactions;
    private Diagnostics diagnostics;
    private RefundService service;

    @BeforeEach void setup() {
        refunds=mock(RefundRepository.class);payments=mock(PaymentRepository.class);runtime=mock(RuntimeServices.class);
        clock=mock(DatabaseTimeRepository.class);close=mock(OrderCloseService.class);
        transactions=mock(PlatformTransactionManager.class);diagnostics=mock(Diagnostics.class);
        when(transactions.getTransaction(any())).thenAnswer(call->new SimpleTransactionStatus());
        when(runtime.productEnabled()).thenReturn(true);when(runtime.simulatorEnabled()).thenReturn(true);
        when(runtime.afterLocks(clock)).thenReturn(NOW);
        var order=new HashMap<String,Object>();
        order.put("id",22L);order.put("user_id",33L);order.put("session_id",44L);order.put("tier_id",55L);
        order.put("status","REFUNDING");order.put("current_refund_id",91L);order.put("paid_attempt_id",11L);
        order.put("current_attempt_id",11L);order.put("paid_at",NOW.minusSeconds(1));
        order.put("payable_cent",500);order.put("currency","CNY");order.put("request_id",66L);
        var source=Map.<String,Object>of("status","SUCCEEDED","order_id",22L,"user_id",33L,"session_id",44L,"tier_id",55L);
        when(close.lockOrder(22L,null)).thenReturn(new OrderCloseService.LockedOrder(Map.of("available",9),Map.of("owner_type","ORDER","owner_id",22L),source,order));
        var unsigned=new PaymentAttemptPO(11,22,33,44,"payment-key",1,500,"CNY",NOW,"SUCCESS",NOW,NOW.plusSeconds(1800),
            "SUCCESS","result-123",NOW,"SIM_SUCCESS",null,"APPLIED",NOW,null,null,null,null);
        var paid=new PaymentAttemptPO(11,22,33,44,"payment-key",1,500,"CNY",NOW,"SUCCESS",NOW,NOW.plusSeconds(1800),
            "SUCCESS","result-123",NOW,"SIM_SUCCESS",PaymentServiceImpl.digest(unsigned),"APPLIED",NOW,null,null,null,null);
        when(payments.lockAttempt(11)).thenReturn(paid);
        when(payments.reservationLedger(22)).thenReturn(List.of(new PaymentLedgerPO("RESERVE",-1,55)));
        when(refunds.orderId(91)).thenReturn(22L);
        when(refunds.observePending(91)).thenReturn(new RefundGenerationPO(2,0,NOW));
        when(refunds.lockRefund(91)).thenReturn(refund(2));
        service=new RefundServiceImpl(refunds,payments,clock,runtime,close,diagnostics,transactions,true);
    }
    private static RefundRequestPO refund(long generation) {
        return new RefundRequestPO(91,22,33,44,11,"refund-key",500,"CNY","changed plans","PENDING",NOW,null,NOW,0,generation,null,null);
    }

    @Test void refundPreservesLockPrefixAndAtomicWriteOrder() {
        service.settle(91);
        var sequence=inOrder(close,payments,refunds,runtime,transactions);
        sequence.verify(close).lockOrder(22L,null);
        sequence.verify(payments).lockAttempt(11);
        sequence.verify(refunds).lockRefund(91);
        sequence.verify(runtime).afterLocks(clock);
        sequence.verify(refunds).succeed(91,2,NOW);
        sequence.verify(refunds).markOrderRefunded(22,91,NOW);
        sequence.verify(refunds).releaseStock(55);
        sequence.verify(refunds).releaseQuota(33,44,22,NOW);
        sequence.verify(refunds).recordRelease(55,22,10,NOW);
        sequence.verify(transactions).commit(any());
        verifyNoInteractions(diagnostics);
    }

    @Test void oldRefundWorkerDoesNotAdoptAConcurrentRecoveryGeneration() {
        when(refunds.lockRefund(91)).thenReturn(refund(3));
        service.settle(91);
        verify(runtime,never()).afterLocks(any());
        verify(refunds,never()).succeed(anyLong(),anyLong(),any());
        verify(refunds,never()).scheduleRetry(anyLong(),anyLong(),anyInt(),any());
        verifyNoInteractions(diagnostics);
    }

    @Test void failureRollsBackAndCannotChargeANewGenerationWithOldFailure() {
        when(refunds.lockRefund(91)).thenReturn(refund(2),refund(3));
        doThrow(new IllegalStateException("INJECTED_AFTER_REFUND" )).when(runtime).at("R1_AFTER_REFUND",91L);
        service.settle(91);
        verify(transactions).rollback(any());
        verify(refunds,never()).markOrderRefunded(anyLong(),anyLong(),any());
        verify(refunds,never()).scheduleRetry(anyLong(),anyLong(),anyInt(),any());
        verify(refunds,never()).exhaustRetries(anyLong(),anyLong(),anyInt());
        verify(diagnostics).record(any());
    }
}
