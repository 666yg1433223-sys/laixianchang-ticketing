package com.ticketing.booking.catalogue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.booking.config.RuntimeServices;
import com.ticketing.booking.domain.ApiFailure;
import com.ticketing.booking.domain.DatabaseTimeSource;
import com.ticketing.booking.model.po.EventPO;
import com.ticketing.booking.model.po.SessionPO;
import com.ticketing.booking.model.po.TierPO;
import com.ticketing.booking.repository.CatalogueRepository;
import com.ticketing.booking.service.AuthService;
import com.ticketing.booking.service.CatalogueService;
import com.ticketing.booking.service.impl.CatalogueServiceImpl;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CatalogueServiceOfflineTest {
    CatalogueRepository catalogue;
    RuntimeServices runtime;
    DatabaseTimeSource clock;
    AuthService auth;
    CatalogueInvalidator invalidator;
    PlatformTransactionManager manager;
    CatalogueService service;
    final Instant now = Instant.parse("2026-09-13T10:00:00Z");

    @BeforeEach void setup() {
        catalogue = mock(CatalogueRepository.class);
        runtime = mock(RuntimeServices.class);
        clock = mock(DatabaseTimeSource.class);
        auth = mock(AuthService.class);
        invalidator = mock(CatalogueInvalidator.class);
        manager = mock(PlatformTransactionManager.class);
        when(runtime.afterLocks(clock)).thenReturn(now);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new CatalogueServiceImpl(catalogue, runtime, clock, auth, mock(CatalogueCache.class), invalidator, manager);
    }

    private SessionPO session(String state) {
        return new SessionPO(5L, 2L, state, now.plusSeconds(120), now.minusSeconds(60), now.plusSeconds(60));
    }

    @Test void nonAdminNeverReachesCatalogueDatabase() {
        doThrow(new ApiFailure(403, "FORBIDDEN")).when(auth).requireAdmin(9L);
        assertEquals(403, assertThrows(ApiFailure.class, () -> service.createEvent(9, "{\"title\":\"X\"}")).status());
        verifyNoInteractions(catalogue);
    }

    @Test void publishedSessionTimesAreFrozen() {
        when(catalogue.lockSession(5L)).thenReturn(session("OPEN"));
        assertEquals("PUBLISHED_FIELDS_FROZEN", assertThrows(ApiFailure.class, () -> service.editSession(9, 5, "{\"startAt\":\"2026-09-14T12:00:00Z\"}")).code());
        verify(catalogue, never()).updateSessionWindow(anyLong(), any(), any(), any());
        verifyNoInteractions(invalidator);
    }

    @Test void pausedSessionCapacityAndPriceAreStillFrozen() {
        when(catalogue.findTierSessionId(8L)).thenReturn(5L);
        when(catalogue.lockSession(5L)).thenReturn(session("PAUSED"));
        when(catalogue.lockTier(8L, 5L)).thenReturn(new TierPO(8L, 5L, "A", "", 5, 3, 100, "CNY"));
        assertEquals("PUBLISHED_FIELDS_FROZEN", assertThrows(ApiFailure.class, () -> service.editTier(9, 8, "{\"capacity\":99}")).code());
        assertEquals("PUBLISHED_FIELDS_FROZEN", assertThrows(ApiFailure.class, () -> service.editTier(9, 8, "{\"unitPriceCent\":99}")).code());
    }

    @Test void publishedSessionCannotAcceptNewTier() {
        when(catalogue.lockSession(5L)).thenReturn(session("OPEN"));
        assertEquals("PUBLISHED_FIELDS_FROZEN", assertThrows(ApiFailure.class, () -> service.createTier(9, "{\"sessionId\":\"5\",\"name\":\"A\",\"capacity\":5,\"unitPriceCent\":100}")).code());
    }

    @Test void publicationRequiresAtLeastOneNonemptyValidTier() {
        when(catalogue.lockSession(5L)).thenReturn(session("DRAFT"));
        when(catalogue.countPublishableTiers(5L)).thenReturn(0L);
        assertEquals("TICKET_TIER_REQUIRED", assertThrows(ApiFailure.class, () -> service.state(9, 5, "publish", "{}")).code());
    }

    @Test void expiredSessionCannotBeResumed() {
        when(catalogue.lockSession(5L)).thenReturn(new SessionPO(5L, 2L, "PAUSED", now.plusSeconds(60), now.minusSeconds(60), now));
        assertEquals("INVALID_BOOKING_WINDOW", assertThrows(ApiFailure.class, () -> service.state(9, 5, "resume", "{}")).code());
    }

    @Test void eachSuccessfulMutationPersistsIndependentInvalidationAndDeletesAfterCommit() {
        when(catalogue.insertEvent("X", "", "")).thenReturn(5L);
        when(catalogue.findEvent(5L)).thenReturn(new EventPO(5L, "X", "", ""));
        when(catalogue.insertMutation(5L, null, now)).thenReturn(101L);
        when(catalogue.insertInvalidation(eq(101L), anyString(), eq(now))).thenReturn(202L);
        assertEquals("5", service.createEvent(9, "{\"title\":\"X\"}").eventId());
        verify(catalogue).insertMutation(5L, null, now);
        verify(catalogue).insertInvalidation(101L, "{\"eventId\":\"5\",\"sessionId\":null}", now);
        var ordered = inOrder(manager, invalidator);
        ordered.verify(manager).commit(any());
        ordered.verify(invalidator).deliver(202L);
    }

    @Test void failedOutboxInsertRollsBackEditAndNeverDeletesCache() {
        when(catalogue.insertEvent("X", "", "")).thenReturn(5L);
        when(catalogue.findEvent(5L)).thenReturn(new EventPO(5L, "X", "", ""));
        when(catalogue.insertInvalidation(anyLong(), anyString(), eq(now))).thenThrow(new IllegalStateException("outbox unavailable"));
        assertThrows(IllegalStateException.class, () -> service.createEvent(9, "{\"title\":\"X\"}"));
        verify(manager).rollback(any());
        verify(manager, never()).commit(any());
        verifyNoInteractions(invalidator);
    }

    @Test void tierRenameLocksSessionBeforeTierAndPreservesSoldAvailability() {
        when(catalogue.findTierSessionId(8L)).thenReturn(5L);
        when(catalogue.lockSession(5L)).thenReturn(session("OPEN"));
        var original = new TierPO(8L, 5L, "A", "", 5, 3, 100, "CNY");
        when(catalogue.lockTier(8L, 5L)).thenReturn(original);
        when(catalogue.findTier(8L)).thenReturn(new TierPO(8L, 5L, "B", "", 5, 3, 100, "CNY"));
        assertEquals("B", service.editTier(9, 8, "{\"name\":\"B\"}").name());
        var ordered = inOrder(catalogue);
        ordered.verify(catalogue).findTierSessionId(8L);
        ordered.verify(catalogue).lockSession(5L);
        ordered.verify(catalogue).lockTier(8L, 5L);
        ordered.verify(catalogue).updateTier(8L, "B", "", 5, 3, 100);
    }

    @Test void strictJsonRejectsDuplicateUnknownAndNullInputFields() {
        for (String input : List.of("{\"title\":\"X\",\"role\":\"ADMIN\"}", "{\"title\":\"X\",\"title\":\"Y\"}",
                                   "{\"title\":null}", "{\"title\":\"X\"} {}")) {
            assertEquals("INVALID_ARGUMENT", assertThrows(ApiFailure.class, () -> service.createEvent(9, input)).code());
        }
        verifyNoInteractions(catalogue);
    }

    @Test void administratorResponseRetainsNestedFieldsStringIdsAndMillisecondUtcTimes() throws Exception {
        when(catalogue.findAdminEvents(11, 0)).thenReturn(List.of(new EventPO(9007199254740993L, "X", null, "剧场")));
        when(catalogue.findAdminSessions(9007199254740993L)).thenReturn(List.of(session("OPEN")));
        when(catalogue.findTiers(5L)).thenReturn(List.of(new TierPO(8L, 5L, "A", "前区", 5, 3, 100, "CNY")));
        ObjectMapper json = new ObjectMapper();
        var actual = json.valueToTree(service.adminEvents(9, 1, 10));
        var expected = json.readTree("""
            {"items":[{"eventId":"9007199254740993","title":"X","description":"","venueText":"剧场",
              "sessions":[{"sessionId":"5","eventId":"2","state":"OPEN","startAt":"2026-09-13T10:02:00.000Z",
                "bookingOpenAt":"2026-09-13T09:59:00.000Z","bookingCloseAt":"2026-09-13T10:01:00.000Z",
                "ticketTiers":[{"tierId":"8","sessionId":"5","name":"A","areaLabel":"前区","unitPriceCent":100,
                  "currency":"CNY","referenceAvailable":3,"capacity":5}]}]}],"page":1,"size":10,"hasNext":false}
            """);
        assertEquals(expected, actual);
    }
}
