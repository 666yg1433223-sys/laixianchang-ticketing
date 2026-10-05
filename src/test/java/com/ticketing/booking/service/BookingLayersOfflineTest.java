package com.ticketing.booking.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.booking.config.Diagnostics;
import com.ticketing.booking.config.RuntimeServices;
import com.ticketing.booking.domain.ApiFailure;
import com.ticketing.booking.model.dto.CreateBookingDTO;
import com.ticketing.booking.model.po.BookingRows.*;
import com.ticketing.booking.model.vo.*;
import com.ticketing.booking.repository.*;
import com.ticketing.booking.service.impl.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Regression boundaries for the structure refactor: HTTP shape, lock order and durable writes. */
class BookingLayersOfflineTest {
    private static final Instant NOW=Instant.parse("2030-01-01T10:00:00Z");
    private final ObjectMapper json=new ObjectMapper();
    private static Set<String> keys(JsonNode node) { var names=new HashSet<String>();node.fieldNames().forEachRemaining(names::add);return names; }
    private static Order order(boolean product,String state) {
        return new Order(88L,11L,null,7L,8L,9L,state,100,1,100,"CNY",NOW,NOW.plusSeconds(600),null,null,null,null,null,null,null,product?NOW.plusSeconds(3600):null,product?NOW:null,product,product);
    }
    @Test void legacyAndProductOrderJsonKeepTheirDistinctOptionalFields() {
        JsonNode legacy=json.valueToTree(OrderVO.from(order(false,"HOLD"),null));
        assertEquals(Set.of("orderId","requestId","sessionId","tierId","orderStatus","unitPriceCent","quantity","payableCent","currency","holdCreatedAt","payExpiresAt","closedAt","closeReason","currentAttemptId","paidAttemptId","paidAt"),keys(legacy));
        assertTrue(legacy.path("orderId").isTextual());assertTrue(legacy.path("closedAt").isNull());
        assertEquals("2030-01-01T10:00:00.000Z",legacy.path("holdCreatedAt").asText());
        JsonNode product=json.valueToTree(OrderVO.from(order(true,"HOLD"),false));
        assertTrue(product.has("waitlistId"));assertTrue(product.path("waitlistId").isNull());
        assertTrue(product.has("currentRefundId"));assertTrue(product.path("currentRefundId").isNull());
        assertTrue(product.path("refundedAt").isNull());assertFalse(product.path("refundEligible").asBoolean());
        assertFalse(product.has("productFields"));
    }
    @Test void orderListRemainsFlatAndBookingStatusDoesNotGainRouteIdentifiers() {
        var order=OrderVO.from(order(true,"HOLD"),false);
        JsonNode item=json.valueToTree(new OrderListItemVO(order,"99","Concert","Standing", "2030-01-01T11:00:00.000Z"));
        assertEquals("88",item.path("orderId").asText());assertEquals("99",item.path("eventId").asText());assertFalse(item.has("order"));
        var embedded=new BookingOrderVO(order.orderId(),order.orderStatus(),100,1,100,"CNY",order.holdCreatedAt(),order.payExpiresAt(),null,null,null,null,null,Map.of());
        JsonNode status=json.valueToTree(new BookingStatusVO("11","SUCCEEDED",null,order.holdCreatedAt(),order.payExpiresAt(),order.holdCreatedAt(),embedded));
        assertTrue(status.path("reasonCode").isNull());assertFalse(status.path("order").has("requestId"));assertFalse(status.path("order").has("productFields"));
    }
    @Test void matchingIdempotencyReturnsBeforeLimitersOrTransactions() throws Exception {
        var repository=mock(BookingRepository.class);var clock=mock(DatabaseTimeRepository.class);var runtime=mock(RuntimeServices.class);
        var limiter=mock(EntryLimiter.class);var allocation=mock(AllocationService.class);var manager=mock(PlatformTransactionManager.class);
        byte[] hash=MessageDigest.getInstance("SHA-256").digest("v1|booking|user=7|session=8|tier=9|quantity=1".getBytes(StandardCharsets.UTF_8));
        when(repository.findIdempotency(7,"valid-key")).thenReturn(new Request(11,7,8,9,hash,"ACCEPTED",null,NOW,NOW.plusSeconds(120),null));
        var service=new BookingServiceImpl(repository,clock,runtime,limiter,allocation,manager);
        assertEquals("11",service.accept(new CreateBookingDTO(7,8,9,"valid-key")).requestId());
        verifyNoInteractions(clock,runtime,limiter,allocation,manager);
        assertEquals(409,assertThrows(ApiFailure.class,()->service.accept(new CreateBookingDTO(7,8,10,"valid-key"))).status());
    }
    @Test void allocationAcquiresBusinessLocksBeforeTimeAndCommitsRequestWithOutbox() {
        var repository=mock(AllocationRepository.class);var clock=mock(DatabaseTimeRepository.class);var runtime=mock(RuntimeServices.class);var diagnostics=mock(Diagnostics.class);
        var manager=mock(PlatformTransactionManager.class);var transaction=new SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenReturn(transaction);
        var request=new Request(11,7,8,9,new byte[32],"ACCEPTED",null,NOW,NOW.plusSeconds(120),null);
        when(repository.requestRoute(11)).thenReturn(request);when(repository.lockRequest(11)).thenReturn(request);
        when(repository.lockSession(8)).thenReturn(new Session(8,"OPEN",NOW.minusSeconds(1),NOW.plusSeconds(3600)));
        when(repository.lockTier(9,8)).thenReturn(new Tier(9,8,100,2,2));when(repository.lockQuota(7,8)).thenReturn(new Quota(7,8,"NONE",null));
        when(repository.lockRequestedEvent(11)).thenReturn(new Outbox(12,"READY",0,NOW,0));when(runtime.afterLocks(clock)).thenReturn(NOW);
        when(repository.reserveTicket(9)).thenReturn(1);when(repository.available(9)).thenReturn(1);
        when(repository.insertOrder(11,7,8,9,100,NOW,NOW.plusSeconds(600))).thenReturn(88L);
        when(repository.claimOrderQuota(88,NOW,7,8)).thenReturn(1);when(repository.finishRequest(11,"SUCCEEDED",null,88L,NOW)).thenReturn(1);when(repository.finishEvent(12,"DONE",NOW)).thenReturn(1);
        var service=new AllocationServiceImpl(repository,clock,runtime,manager,diagnostics);service.process(11);
        var sequence=inOrder(repository,runtime,manager);
        sequence.verify(manager).getTransaction(argThat(def->def.getIsolationLevel()==TransactionDefinition.ISOLATION_READ_COMMITTED && def.getTimeout()==8));
        sequence.verify(repository).requestRoute(11);sequence.verify(repository).lockSession(8);sequence.verify(runtime).at("T2_SESSION_LOCKED",11);
        sequence.verify(repository).lockTier(9,8);sequence.verify(repository).ensureQuota(7,8);sequence.verify(repository).lockQuota(7,8);
        sequence.verify(repository).lockRequest(11);sequence.verify(repository).lockRequestOrder(11);sequence.verify(repository).lockRequestedEvent(11);
        sequence.verify(runtime).at("T2_LOCKS_ACQUIRED",11);sequence.verify(runtime).afterLocks(clock);
        sequence.verify(repository).finishRequest(11,"SUCCEEDED",null,88L,NOW);sequence.verify(repository).finishEvent(12,"DONE",NOW);sequence.verify(manager).commit(transaction);
        assertEquals(0,service.technicalFailures());verifyNoInteractions(diagnostics);
    }
    @Test void terminalAllocationNeverReservesAgain() {
        var repository=mock(AllocationRepository.class);var runtime=mock(RuntimeServices.class);var diagnostics=mock(Diagnostics.class);var clock=mock(DatabaseTimeRepository.class);var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var request=new Request(11,7,8,9,new byte[32],"SUCCEEDED",null,NOW,NOW.plusSeconds(120),88L);
        when(repository.requestRoute(11)).thenReturn(request);when(repository.lockRequest(11)).thenReturn(request);
        when(repository.lockSession(8)).thenReturn(new Session(8,"OPEN",NOW,NOW.plusSeconds(3600)));
        when(repository.lockTier(9,8)).thenReturn(new Tier(9,8,100,1,2));when(repository.lockQuota(7,8)).thenReturn(new Quota(7,8,"ORDER",88L));
        when(repository.lockRequestOrder(11)).thenReturn(order(false,"HOLD"));when(repository.lockRequestedEvent(11)).thenReturn(new Outbox(12,"DONE",0,null,0));
        new AllocationServiceImpl(repository,clock,runtime,manager,diagnostics).process(11);
        verify(repository,never()).reserveTicket(anyLong());verify(runtime,never()).afterLocks(any());verifyNoInteractions(diagnostics);
    }
    @Test void closingAtDeadlineExpiresAndReleasesExactlyOnce() {
        var repository=mock(OrderRepository.class);var clock=mock(DatabaseTimeRepository.class);var runtime=mock(RuntimeServices.class);
        var service=new OrderCloseServiceImpl(repository,clock,runtime,mock(PlatformTransactionManager.class));
        var locked=new OrderCloseService.LockedOrder(new Tier(9,8,100,1,2).legacy(),new Quota(7,8,"ORDER",88L).legacy(),new Request(11,7,8,9,new byte[32],"SUCCEEDED",null,NOW,NOW.plusSeconds(120),88L).legacy(),order(false,"HOLD").legacy());
        when(repository.ledger(88)).thenReturn(List.of(new Ledger("RESERVE",-1,9)));
        when(repository.closeHold(88,"EXPIRED","PAYMENT_DEADLINE_EXCEEDED",NOW.plusSeconds(600))).thenReturn(1);
        when(repository.releaseTicket(9)).thenReturn(1);when(repository.releaseQuota(7,8,88,NOW.plusSeconds(600))).thenReturn(1);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            var first=service.closeLocked(locked,NOW.plusSeconds(600),true);var again=service.closeLocked(locked,NOW.plusSeconds(601),true);
            assertEquals("EXPIRED",first.get("orderStatus"));assertEquals(first,again);
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        verify(repository,times(1)).releaseTicket(9);verify(repository,times(1)).insertRelease(9,88,2,NOW.plusSeconds(600));
    }
    @Test void invalidReservationLedgerCannotReleaseStock() {
        var repository=mock(OrderRepository.class);var service=new OrderCloseServiceImpl(repository,mock(DatabaseTimeRepository.class),mock(RuntimeServices.class),mock(PlatformTransactionManager.class));
        var locked=new OrderCloseService.LockedOrder(new Tier(9,8,100,1,2).legacy(),new Quota(7,8,"ORDER",88L).legacy(),new Request(11,7,8,9,new byte[32],"SUCCEEDED",null,NOW,NOW.plusSeconds(120),88L).legacy(),order(false,"HOLD").legacy());
        when(repository.ledger(88)).thenReturn(List.of(new Ledger("RESERVE",-1,9),new Ledger("RELEASE",1,9)));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertEquals("CLOSE_LEDGER_INCONSISTENT",assertThrows(IllegalStateException.class,()->service.closeLocked(locked,NOW,true)).getMessage()); }
        finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        verify(repository,never()).releaseTicket(anyLong());verify(repository,never()).closeHold(anyLong(),anyString(),anyString(),any());
    }
    @Test void waitlistAdvancesOnlyTheCommittedHeadAndTransfersItsQuota() {
        var repository=mock(WaitlistRepository.class);var runtime=mock(RuntimeServices.class);var clock=mock(DatabaseTimeRepository.class);var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(repository.tierSession(9)).thenReturn(8L);when(repository.lockSession(8)).thenReturn(new Session(8,"OPEN",NOW.minusSeconds(60),NOW.plusSeconds(3600)));
        when(repository.lockTier(9)).thenReturn(new Tier(9,8,100,1,2));
        var head=new Waitlist(21,7,8,9,new byte[32],"WAITING",null,NOW.minusSeconds(10));
        when(repository.firstWaiting(9)).thenReturn(head);when(repository.lockEntry(21)).thenReturn(head);
        when(repository.lockQuota(7,8)).thenReturn(new Quota(7,8,"WAITLIST",21L));when(runtime.afterLocks(clock)).thenReturn(NOW);
        when(repository.reserveTicket(9)).thenReturn(1);when(repository.insertOrder(21,7,8,9,100,NOW,NOW.plusSeconds(600))).thenReturn(88L);
        when(repository.allocateQuota(88,7,8,21,NOW)).thenReturn(1);when(repository.allocateEntry(21,88,NOW)).thenReturn(1);
        new WaitlistServiceImpl(repository,clock,runtime,mock(EntryLimiter.class),manager).advance(9);
        var sequence=inOrder(repository,runtime,manager);
        sequence.verify(repository).lockSession(8);sequence.verify(repository).lockTier(9);sequence.verify(repository).firstWaiting(9);
        sequence.verify(repository).lockQuota(7,8);sequence.verify(repository).lockEntry(21);sequence.verify(runtime).afterLocks(clock);
        sequence.verify(repository).reserveTicket(9);sequence.verify(repository).insertOrder(21,7,8,9,100,NOW,NOW.plusSeconds(600));
        sequence.verify(repository).allocateQuota(88,7,8,21,NOW);sequence.verify(repository).allocateEntry(21,88,NOW);
        sequence.verify(repository).insertReservation(9,88,0,NOW);sequence.verify(manager).commit(any());
    }
    @Test void waitlistOwnerMismatchRollsBackBeforeInventoryIsTouched() {
        var repository=mock(WaitlistRepository.class);var runtime=mock(RuntimeServices.class);var clock=mock(DatabaseTimeRepository.class);var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(repository.tierSession(9)).thenReturn(8L);when(repository.lockSession(8)).thenReturn(new Session(8,"OPEN",NOW.minusSeconds(60),NOW.plusSeconds(3600)));
        when(repository.lockTier(9)).thenReturn(new Tier(9,8,100,1,2));
        var head=new Waitlist(21,7,8,9,new byte[32],"WAITING",null,NOW.minusSeconds(10));
        when(repository.firstWaiting(9)).thenReturn(head);when(repository.lockEntry(21)).thenReturn(head);when(repository.lockQuota(7,8)).thenReturn(new Quota(7,8,"ORDER",88L));
        var service=new WaitlistServiceImpl(repository,clock,runtime,mock(EntryLimiter.class),manager);
        assertEquals("WAITLIST_OWNER_INCONSISTENT",assertThrows(IllegalStateException.class,()->service.advance(9)).getMessage());
        verify(repository,never()).reserveTicket(anyLong());verify(manager).rollback(any());verify(runtime,never()).afterLocks(any());
    }
}
