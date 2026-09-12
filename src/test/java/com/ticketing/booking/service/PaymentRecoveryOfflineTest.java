package com.ticketing.booking.service;

import com.ticketing.booking.auth.PaymentManagementAccess.Operator;
import com.ticketing.booking.config.RuntimeServices;
import com.ticketing.booking.config.Diagnostics;
import com.ticketing.booking.domain.ApiFailure;
import com.ticketing.booking.persistence.BookingStore;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentRecoveryOfflineTest {
    private static final Instant DUE=Instant.parse("2030-06-01T10:00:00Z");
    private Map<String,Object> event(long round,int attempts,Instant due,String state) {
        var row=new HashMap<String,Object>();row.put("recovery_generation",round);row.put("attempts",attempts);
        row.put("next_attempt_at",due);row.put("state",state);return row;
    }
    @Test void onlyExactRoundAndRetrySlotCanExecuteOrRecordFailure() {
        var observed=new PaymentService.Generation(7,2,DUE);
        assertTrue(observed.matches(event(7,2,DUE,"READY")));
        assertFalse(observed.matches(event(8,2,DUE,"READY")),"same counter and millisecond cannot defeat the round fence");
        assertFalse(observed.matches(event(7,3,DUE,"READY")));
        assertFalse(observed.matches(event(7,2,DUE.plusMillis(1),"READY")));
        assertFalse(observed.matches(event(7,2,null,"DEAD")));
        assertFalse(observed.matches(event(7,2,null,"DONE")));
    }
    @Test void normalizedReasonIsBoundedAndRejectsControlCharacters() {
        assertEquals("restored upstream",PaymentManagementService.normalizeReason("  restored upstream  "));
        for(String value:new String[]{"", " ", "a\nb", "a\u0000b", "x".repeat(201)}) {
            assertThrows(ApiFailure.class,()->PaymentManagementService.normalizeReason(value));
        }
        assertThrows(ApiFailure.class,()->PaymentManagementService.normalizeReason(null));
        assertEquals(200,PaymentManagementService.normalizeReason("😀".repeat(200)).codePointCount(0,400));
    }
    @Test void idempotencyFingerprintBindsEveryBusinessParameter() {
        byte[] expected=PaymentManagementService.fingerprint("RECOVER","12","fixed",0);
        assertArrayEquals(expected,PaymentManagementService.fingerprint("RECOVER","12","fixed",0));
        assertFalse(java.util.Arrays.equals(expected,PaymentManagementService.fingerprint("RECOVER","13","fixed",0)));
        assertFalse(java.util.Arrays.equals(expected,PaymentManagementService.fingerprint("REDELIVER","12","fixed",0)));
        assertFalse(java.util.Arrays.equals(expected,PaymentManagementService.fingerprint("RECOVER","12","different",0)));
        assertFalse(java.util.Arrays.equals(expected,PaymentManagementService.fingerprint("RECOVER","12","fixed",1)));
    }
    @Test void foreignTargetIsRejectedBeforeAnyStoreOrPaymentInteraction() {
        var store=mock(BookingStore.class);var payment=mock(PaymentService.class);
        var service=new PaymentManagementService(store,payment,mock(RuntimeServices.class),mock(PlatformTransactionManager.class));
        var actor=new Operator(10,Set.of(123L));
        ApiFailure failure=assertThrows(ApiFailure.class,()->service.recover(actor,456,"valid-key","restored"));
        assertEquals(404,failure.status());verifyNoInteractions(store,payment);
    }
    @Test void emptyScopeNeverLoadsRowsAndQueriesNeverStartWork() {
        var store=mock(BookingStore.class);var payment=mock(PaymentService.class);
        var service=new PaymentManagementService(store,payment,mock(RuntimeServices.class),mock(PlatformTransactionManager.class));
        var actor=new Operator(10,Set.of());
        assertEquals(java.util.List.of(),service.list(actor,0,20).get("items"));
        assertEquals(404,assertThrows(ApiFailure.class,()->service.query(actor,"12345678-1234-1234-1234-123456789abc")).status());
        assertEquals(404,assertThrows(ApiFailure.class,()->service.redeliver(actor,"12345678-1234-1234-1234-123456789abc","valid-key","restored",1)).status());
        verifyNoInteractions(store,payment);
    }
    @Test void realSettlementDoesNotAdoptNewRoundAfterWaitingForLocks() {
        var store=mock(BookingStore.class);var runtime=mock(RuntimeServices.class);
        var close=mock(OrderCloseService.class);var diagnostics=mock(Diagnostics.class);
        var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(store.optional(startsWith("SELECT recovery_generation"),eq(1L))).thenReturn(event(7,0,DUE,"READY"));
        when(store.required(startsWith("SELECT order_id"),eq(1L))).thenReturn(Map.of("order_id",2L));
        when(close.lockOrder(2L,null)).thenReturn(new OrderCloseService.LockedOrder(Map.of(),Map.of(),Map.of(),Map.of()));
        when(store.required(startsWith("SELECT * FROM payment_attempt"),eq(1L))).thenReturn(Map.of("id",1L));
        when(store.optional(startsWith("SELECT * FROM outbox_event"),eq(1L))).thenReturn(event(8,0,DUE,"READY"));
        new PaymentService(store,runtime,close,diagnostics,manager,true).settle(1);
        verify(runtime,never()).afterLocks(any());verify(store,never()).jdbc();verifyNoInteractions(diagnostics);
    }
    @Test void lateFailureRecorderDoesNotCountAgainstNewRound() {
        var store=mock(BookingStore.class);var runtime=mock(RuntimeServices.class);
        var close=mock(OrderCloseService.class);var diagnostics=mock(Diagnostics.class);
        var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(call->new SimpleTransactionStatus());
        when(store.optional(startsWith("SELECT recovery_generation"),eq(1L))).thenReturn(event(7,0,DUE,"READY"));
        when(store.required(startsWith("SELECT order_id"),eq(1L)))
            .thenThrow(new IllegalStateException("INJECTED_OLD_LOCK_FAILURE")).thenReturn(Map.of("order_id",2L));
        when(close.lockOrder(2L,null)).thenReturn(new OrderCloseService.LockedOrder(Map.of(),Map.of(),Map.of(),Map.of()));
        when(store.required(startsWith("SELECT * FROM payment_attempt"),eq(1L)))
            .thenReturn(Map.of("id",1L,"settlement_status","UNRESOLVED"));
        when(store.required(startsWith("SELECT * FROM outbox_event"),eq(1L))).thenReturn(event(8,0,DUE,"READY"));
        new PaymentService(store,runtime,close,diagnostics,manager,true).settle(1);
        verify(runtime).at("P2_BEFORE_FAILURE_RECORD",1L);
        verify(runtime,never()).afterLocks(any());verify(store,never()).jdbc();
        verify(diagnostics,times(1)).record(any());
    }
}
