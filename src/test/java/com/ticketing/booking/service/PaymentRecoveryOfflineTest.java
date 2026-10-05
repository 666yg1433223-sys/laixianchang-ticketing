package com.ticketing.booking.service;

import com.ticketing.booking.security.PaymentManagementAccess.Operator;
import com.ticketing.booking.config.RuntimeServices;
import com.ticketing.booking.config.Diagnostics;
import com.ticketing.booking.domain.ApiFailure;
import com.ticketing.booking.repository.DatabaseTimeRepository;
import com.ticketing.booking.repository.PaymentManagementRepository;
import com.ticketing.booking.repository.PaymentRepository;
import com.ticketing.booking.model.po.PaymentAttemptPO;
import com.ticketing.booking.model.po.PaymentResultEventPO;
import com.ticketing.booking.service.impl.PaymentServiceImpl;
import com.ticketing.booking.service.impl.PaymentManagementServiceImpl;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentRecoveryOfflineTest {
    private static final Instant DUE=Instant.parse("2030-06-01T10:00:00Z");
    private PaymentResultEventPO event(long round,int attempts,Instant due,String state) {
        return new PaymentResultEventPO(4,1,1L,state,round,attempts,due,null);
    }
    @Test void onlyExactRoundAndRetrySlotCanExecuteOrRecordFailure() {
        var observed=new PaymentServiceImpl.Generation(7,2,DUE);
        assertTrue(observed.matches(event(7,2,DUE,"READY")));
        assertFalse(observed.matches(event(8,2,DUE,"READY")),"same counter and millisecond cannot defeat the round fence");
        assertFalse(observed.matches(event(7,3,DUE,"READY")));
        assertFalse(observed.matches(event(7,2,DUE.plusMillis(1),"READY")));
        assertFalse(observed.matches(event(7,2,null,"DEAD")));
        assertFalse(observed.matches(event(7,2,null,"DONE")));
    }
    @Test void normalizedReasonIsBoundedAndRejectsControlCharacters() {
        assertEquals("restored upstream",PaymentManagementServiceImpl.normalizeReason("  restored upstream  "));
        for(String value:new String[]{"", " ", "a\nb", "a\u0000b", "x".repeat(201)}) {
            assertThrows(ApiFailure.class,()->PaymentManagementServiceImpl.normalizeReason(value));
        }
        assertThrows(ApiFailure.class,()->PaymentManagementServiceImpl.normalizeReason(null));
        assertEquals(200,PaymentManagementServiceImpl.normalizeReason("😀".repeat(200)).codePointCount(0,400));
    }
    @Test void idempotencyFingerprintBindsEveryBusinessParameter() {
        byte[] expected=PaymentManagementServiceImpl.fingerprint("RECOVER","12","fixed",0);
        assertArrayEquals(expected,PaymentManagementServiceImpl.fingerprint("RECOVER","12","fixed",0));
        assertFalse(java.util.Arrays.equals(expected,PaymentManagementServiceImpl.fingerprint("RECOVER","13","fixed",0)));
        assertFalse(java.util.Arrays.equals(expected,PaymentManagementServiceImpl.fingerprint("REDELIVER","12","fixed",0)));
        assertFalse(java.util.Arrays.equals(expected,PaymentManagementServiceImpl.fingerprint("RECOVER","12","different",0)));
        assertFalse(java.util.Arrays.equals(expected,PaymentManagementServiceImpl.fingerprint("RECOVER","12","fixed",1)));
    }
    @Test void foreignTargetIsRejectedBeforeAnyStoreOrPaymentInteraction() {
        var store=mock(PaymentManagementRepository.class);var payment=mock(PaymentService.class);
        var attempts=mock(PaymentRepository.class);var clock=mock(DatabaseTimeRepository.class);
        var service=new PaymentManagementServiceImpl(store,attempts,clock,payment,mock(RuntimeServices.class),mock(PlatformTransactionManager.class));
        var actor=new Operator(10,Set.of(123L));
        ApiFailure failure=assertThrows(ApiFailure.class,()->service.recover(actor,456,"valid-key","restored"));
        assertEquals(404,failure.status());verifyNoInteractions(store,payment,attempts,clock);
    }
    @Test void emptyScopeNeverLoadsRowsAndQueriesNeverStartWork() {
        var store=mock(PaymentManagementRepository.class);var payment=mock(PaymentService.class);
        var attempts=mock(PaymentRepository.class);var clock=mock(DatabaseTimeRepository.class);
        var service=new PaymentManagementServiceImpl(store,attempts,clock,payment,mock(RuntimeServices.class),mock(PlatformTransactionManager.class));
        var actor=new Operator(10,Set.of());
        assertEquals(java.util.List.of(),service.list(actor,0,20).items());
        assertEquals(404,assertThrows(ApiFailure.class,()->service.query(actor,"12345678-1234-1234-1234-123456789abc")).status());
        assertEquals(404,assertThrows(ApiFailure.class,()->service.redeliver(actor,"12345678-1234-1234-1234-123456789abc","valid-key","restored",1)).status());
        verifyNoInteractions(store,payment,attempts,clock);
    }
    @Test void realSettlementDoesNotAdoptNewRoundAfterWaitingForLocks() {
        var store=mock(PaymentRepository.class);var runtime=mock(RuntimeServices.class);var clock=mock(DatabaseTimeRepository.class);
        var close=mock(OrderCloseService.class);var diagnostics=mock(Diagnostics.class);
        var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(store.observeReadyEvent(1L)).thenReturn(event(7,0,DUE,"READY"));
        when(store.orderId(1L)).thenReturn(2L);
        when(close.lockOrder(2L,null)).thenReturn(new OrderCloseService.LockedOrder(Map.of(),Map.of(),Map.of(),Map.of()));
        when(store.lockAttempt(1L)).thenReturn(mock(PaymentAttemptPO.class));
        when(store.lockResultEvent(1L)).thenReturn(event(8,0,DUE,"READY"));
        new PaymentServiceImpl(store,clock,runtime,close,diagnostics,manager,true).settle(1);
        verify(runtime,never()).afterLocks(any());verifyNoInteractions(clock,diagnostics);
        verify(store,never()).markOrderPaid(anyLong(),any(),anyLong());
        verify(store,never()).countFailure(anyLong(),anyInt());
    }
    @Test void lateFailureRecorderDoesNotCountAgainstNewRound() {
        var store=mock(PaymentRepository.class);var runtime=mock(RuntimeServices.class);var clock=mock(DatabaseTimeRepository.class);
        var close=mock(OrderCloseService.class);var diagnostics=mock(Diagnostics.class);
        var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(call->new SimpleTransactionStatus());
        when(store.observeReadyEvent(1L)).thenReturn(event(7,0,DUE,"READY"));
        when(store.orderId(1L))
            .thenThrow(new IllegalStateException("INJECTED_OLD_LOCK_FAILURE")).thenReturn(2L);
        when(close.lockOrder(2L,null)).thenReturn(new OrderCloseService.LockedOrder(Map.of(),Map.of(),Map.of(),Map.of()));
        when(store.lockAttempt(1L)).thenReturn(mock(PaymentAttemptPO.class));
        when(store.requireLockedResultEvent(1L)).thenReturn(event(8,0,DUE,"READY"));
        new PaymentServiceImpl(store,clock,runtime,close,diagnostics,manager,true).settle(1);
        verify(runtime).at("P2_BEFORE_FAILURE_RECORD",1L);
        verify(runtime,never()).afterLocks(any());verifyNoInteractions(clock);
        verify(store,never()).countFailure(anyLong(),anyInt());
        verify(diagnostics,times(1)).record(any());
    }
}
