package com.ticketing.booking.service;

import com.ticketing.booking.domain.ApiFailure;
import com.ticketing.booking.model.dto.ProcessingRetryDTO;
import com.ticketing.booking.model.po.OutboxMessagePO;
import com.ticketing.booking.model.po.ProcessingRetryPO;
import com.ticketing.booking.model.po.ProcessingRoutePO;
import com.ticketing.booking.model.vo.PaymentOperationVO;
import com.ticketing.booking.model.vo.ProcessingRetryAcceptedVO;
import com.ticketing.booking.repository.ProcessingRecoveryRepository;
import com.ticketing.booking.security.PaymentManagementAccess.Operator;
import com.ticketing.booking.service.impl.ProcessingRecoveryServiceImpl;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProcessingRecoveryOfflineTest {
    private static final long ADMIN=31, EVENT=101, REQUEST=201, USER=41, SESSION=51, TIER=61, ATTEMPT=71;
    private static final String KEY="retry:event-101", REASON="dependency restored";
    private static final String OPERATION="abcdef01-abcd-1234-abcd-1234567890ab";
    private static final ProcessingRetryDTO INPUT=new ProcessingRetryDTO(EVENT,KEY,REASON);
    private ProcessingRecoveryRepository repository;
    private AuthService auth;
    private PaymentManagementService payments;
    private PlatformTransactionManager manager;
    private SimpleTransactionStatus transaction;
    private ProcessingRecoveryService service;

    @BeforeEach void setup() {
        repository=mock(ProcessingRecoveryRepository.class);
        auth=mock(AuthService.class);
        payments=mock(PaymentManagementService.class);
        manager=mock(PlatformTransactionManager.class);
        transaction=new SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenReturn(transaction);
        service=new ProcessingRecoveryServiceImpl(repository,auth,payments,manager);
    }

    private static OutboxMessagePO booking(String state) {
        return new OutboxMessagePO(EVENT,"BOOKING_REQUESTED",REQUEST,REQUEST,null,"{}",state,6,"DEPENDENCY_UNAVAILABLE",2);
    }

    private void bookingRoute(String lockedState) {
        when(repository.findMessage(EVENT)).thenReturn(booking("DEAD"));
        when(repository.route(REQUEST)).thenReturn(new ProcessingRoutePO(REQUEST,USER,SESSION,TIER));
        when(repository.lockMessage(EVENT)).thenReturn(booking(lockedState));
        when(repository.reopenRequest(REQUEST)).thenReturn(1);
        when(repository.reopenMessage(EVENT)).thenReturn(1);
        when(repository.recordRetry(EVENT,ADMIN,REASON,KEY)).thenReturn(1);
    }

    private void assertNoRecoveryWrites() {
        verify(repository,never()).reopenRequest(anyLong());
        verify(repository,never()).reopenMessage(anyLong());
        verify(repository,never()).recordRetry(anyLong(),anyLong(),anyString(),anyString());
    }

    @Test void bookingRetryUsesTheSharedBusinessLockOrderAndCommitsWithItsAudit() {
        bookingRoute("DEAD");
        assertEquals(new ProcessingRetryAcceptedVO("101",true),service.retry(ADMIN,INPUT));
        // This order is shared with allocation/close: reversing it can deadlock concurrent workers.
        var ordered=inOrder(auth,repository,manager);
        ordered.verify(auth).requireAdmin(ADMIN);
        ordered.verify(repository).findMessage(EVENT);
        ordered.verify(manager).getTransaction(any());
        ordered.verify(repository).route(REQUEST);
        ordered.verify(repository).lockSession(SESSION);
        ordered.verify(repository).lockTier(TIER);
        ordered.verify(repository).lockQuota(USER,SESSION);
        ordered.verify(repository).lockRequest(REQUEST);
        ordered.verify(repository).lockMessage(EVENT);
        ordered.verify(repository).reopenRequest(REQUEST);
        ordered.verify(repository).reopenMessage(EVENT);
        ordered.verify(repository).recordRetry(EVENT,ADMIN,REASON,KEY);
        ordered.verify(manager).commit(transaction);
        verify(manager,never()).rollback(any());
        verifyNoInteractions(payments);
    }

    @Test void repeatedKeyReturnsTheOriginalAcceptanceAfterTheEventHasLeftDeadState() {
        bookingRoute("READY");
        when(repository.previousRetry(ADMIN,KEY)).thenReturn(new ProcessingRetryPO(EVENT,REASON));
        assertEquals(new ProcessingRetryAcceptedVO("101",true),service.retry(ADMIN,INPUT));
        assertNoRecoveryWrites();
        verify(manager).commit(transaction);
        verify(manager,never()).rollback(any());
    }

    static Stream<Arguments> conflictingKeys() {
        return Stream.of(Arguments.of(EVENT+1,REASON),Arguments.of(EVENT,"a different reason"));
    }

    @ParameterizedTest @MethodSource("conflictingKeys")
    void aKeyCannotBeReusedForAnotherEventOrReason(long previousEvent,String previousReason) {
        bookingRoute("DEAD");
        when(repository.previousRetry(ADMIN,KEY)).thenReturn(new ProcessingRetryPO(previousEvent,previousReason));
        var failure=assertThrows(ApiFailure.class,()->service.retry(ADMIN,INPUT));
        assertEquals(409,failure.status());assertEquals("IDEMPOTENCY_CONFLICT",failure.code());
        assertNoRecoveryWrites();
        verify(manager).rollback(transaction);
        verify(manager,never()).commit(any());
    }

    @Test void expiredRequestCannotReopenItsMessageOrCreateAnAuditAndRollsBack() {
        bookingRoute("DEAD");
        // The repository atomically checks PROCESSING_FAILED and the database deadline in UPDATE.
        when(repository.reopenRequest(REQUEST)).thenReturn(0);
        var failure=assertThrows(ApiFailure.class,()->service.retry(ADMIN,INPUT));
        assertEquals(409,failure.status());assertEquals("PROCESS_DEADLINE_EXCEEDED",failure.code());
        verify(repository).reopenRequest(REQUEST);
        verify(repository,never()).reopenMessage(anyLong());
        verify(repository,never()).recordRetry(anyLong(),anyLong(),anyString(),anyString());
        verify(manager).rollback(transaction);
        verify(manager,never()).commit(any());
    }

    @Test void failureToPersistTheRetryAuditRollsBackTheAlreadyReopenedRequestAndMessage() {
        bookingRoute("DEAD");
        var failure=new IllegalStateException("INJECTED_AUDIT_FAILURE");
        when(repository.recordRetry(EVENT,ADMIN,REASON,KEY)).thenThrow(failure);
        assertSame(failure,assertThrows(IllegalStateException.class,()->service.retry(ADMIN,INPUT)));
        verify(repository).reopenRequest(REQUEST);
        verify(repository).reopenMessage(EVENT);
        verify(manager).rollback(transaction);
        verify(manager,never()).commit(any());
    }

    @Test void ordinaryUserCannotReadOrRetryProcessingFailuresOrPaymentOperations() {
        doThrow(new ApiFailure(403,"FORBIDDEN")).when(auth).requireAdmin(USER);
        assertAll(
            ()->assertEquals(403,assertThrows(ApiFailure.class,()->service.list(USER,1,20)).status()),
            ()->assertEquals(403,assertThrows(ApiFailure.class,()->service.retry(USER,INPUT)).status()),
            ()->assertEquals(403,assertThrows(ApiFailure.class,()->service.operation(USER,"invalid-operation-id")).status()));
        verify(auth,times(3)).requireAdmin(USER);
        verifyNoInteractions(repository,payments,manager);
    }

    private static PaymentOperationVO paymentReceipt() {
        return new PaymentOperationVO(OPERATION,"31","71","3","RECOVER","71",REASON,1,"ACCEPTED",null,
            "REVIEW","DEAD",6,"PAYMENT_TECHNICAL_FAILURE","2030-06-01T10:00:00.000Z",null,null,
            "/v1/internal/sim/operations/"+OPERATION,true);
    }

    @Test void paymentEventUsesOnlyThePersistedAttemptScopeAndReturnsAnAdminStatusUrl() {
        when(repository.findMessage(EVENT)).thenReturn(new OutboxMessagePO(EVENT,"PAYMENT_RESULT_RECORDED",ATTEMPT,
            null,ATTEMPT,"{}","DEAD",6,"PAYMENT_TECHNICAL_FAILURE",2));
        var actor=new Operator(ADMIN,Set.of(ATTEMPT));
        var original=paymentReceipt();
        when(payments.recover(actor,ATTEMPT,KEY,REASON)).thenReturn(original);
        var result=service.retry(ADMIN,INPUT);
        assertEquals(original.withStatusUrl("/v1/admin/processing-failures/operations/"+OPERATION),result);
        assertEquals("/v1/internal/sim/operations/"+OPERATION,original.statusUrl());
        verify(payments).recover(actor,ATTEMPT,KEY,REASON);
        verify(repository).findMessage(EVENT);
        verifyNoMoreInteractions(repository,payments);
        verifyNoInteractions(manager);
    }

    @Test void paymentOperationQueryUsesItsStoredAttemptAndPreservesTheReceiptFields() {
        when(repository.operationAttempt(OPERATION)).thenReturn(ATTEMPT);
        var actor=new Operator(ADMIN,Set.of(ATTEMPT));
        var original=paymentReceipt();
        when(payments.query(actor,OPERATION)).thenReturn(original);
        assertEquals(original.withStatusUrl("/v1/admin/processing-failures/operations/"+OPERATION),service.operation(ADMIN,OPERATION));
        verify(payments).query(actor,OPERATION);
        verifyNoMoreInteractions(payments);
        verifyNoInteractions(manager);
    }
}
