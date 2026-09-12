package com.ticketing.booking.catalogue;

import com.ticketing.booking.auth.AuthService;
import com.ticketing.booking.config.RuntimeServices;
import com.ticketing.booking.domain.ApiFailure;
import com.ticketing.booking.persistence.BookingStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CatalogueServiceOfflineTest {
    BookingStore store;JdbcTemplate jdbc;RuntimeServices runtime;AuthService auth;
    CatalogueInvalidator invalidator;PlatformTransactionManager manager;CatalogueService service;
    final Instant now=Instant.parse("2026-09-13T10:00:00Z");
    @BeforeEach void setup() {
        store=mock(BookingStore.class);jdbc=mock(JdbcTemplate.class);runtime=mock(RuntimeServices.class);auth=mock(AuthService.class);
        invalidator=mock(CatalogueInvalidator.class);manager=mock(PlatformTransactionManager.class);
        when(store.jdbc()).thenReturn(jdbc);when(runtime.afterLocks(jdbc)).thenReturn(now);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service=new CatalogueService(store,runtime,auth,mock(CatalogueCache.class),invalidator,manager);
    }
    @Test void nonAdminNeverReachesCatalogueDatabase() {
        doThrow(new ApiFailure(403,"FORBIDDEN")).when(auth).requireAdmin(9L);
        assertEquals(403,assertThrows(ApiFailure.class,()->service.createEvent(9,"{\"title\":\"X\"}")).status());
        verifyNoInteractions(store);
    }
    @Test void publishedSessionTimesAreFrozen() {
        when(store.optional("SELECT * FROM event_session WHERE id=? FOR UPDATE",5L)).thenReturn(Map.of("state","OPEN"));
        assertEquals("PUBLISHED_FIELDS_FROZEN",assertThrows(ApiFailure.class,()->service.editSession(9,5,"{\"startAt\":\"2026-09-14T12:00:00Z\"}")).code());
        verify(jdbc,never()).update(anyString(),any(Object[].class));verifyNoInteractions(invalidator);
    }
    @Test void pausedSessionCapacityAndPriceAreStillFrozen() {
        when(store.optional("SELECT session_id FROM ticket_tier WHERE id=?",8L)).thenReturn(Map.of("session_id",5L));
        when(store.optional("SELECT * FROM event_session WHERE id=? FOR UPDATE",5L)).thenReturn(Map.of("state","PAUSED"));
        when(store.optional("SELECT * FROM ticket_tier WHERE id=? AND session_id=? FOR UPDATE",8L,5L)).thenReturn(Map.of("id",8L));
        assertEquals("PUBLISHED_FIELDS_FROZEN",assertThrows(ApiFailure.class,()->service.editTier(9,8,"{\"capacity\":99}")).code());
        assertEquals("PUBLISHED_FIELDS_FROZEN",assertThrows(ApiFailure.class,()->service.editTier(9,8,"{\"unitPriceCent\":99}")).code());
    }
    @Test void publishedSessionCannotAcceptNewTier() {
        when(store.optional("SELECT * FROM event_session WHERE id=? FOR UPDATE",5L)).thenReturn(Map.of("state","OPEN"));
        assertEquals("PUBLISHED_FIELDS_FROZEN",assertThrows(ApiFailure.class,()->service.createTier(9,"{\"sessionId\":\"5\",\"name\":\"A\",\"capacity\":5,\"unitPriceCent\":100}")).code());
    }
    @Test void publicationRequiresAtLeastOneNonemptyValidTier() {
        when(store.optional("SELECT * FROM event_session WHERE id=? FOR UPDATE",5L)).thenReturn(Map.of("state","DRAFT"));
        when(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_tier WHERE session_id=? AND capacity>0 AND available=capacity AND unit_price_cent>0",Long.class,5L)).thenReturn(0L);
        assertEquals("TICKET_TIER_REQUIRED",assertThrows(ApiFailure.class,()->service.state(9,5,"publish","{}")).code());
    }
    @Test void expiredSessionCannotBeResumed() {
        when(store.optional("SELECT * FROM event_session WHERE id=? FOR UPDATE",5L)).thenReturn(Map.of("state","PAUSED",
            "booking_open_at",Timestamp.from(now.minusSeconds(60)),"booking_close_at",Timestamp.from(now),"start_at",Timestamp.from(now.plusSeconds(60))));
        assertEquals("INVALID_BOOKING_WINDOW",assertThrows(ApiFailure.class,()->service.state(9,5,"resume","{}")).code());
    }
    @Test void eachSuccessfulMutationPersistsIndependentInvalidationAndDeletesAfterCommit() {
        when(store.insert(anyString(),any(Object[].class))).thenReturn(5L,101L,202L);
        when(store.required("SELECT * FROM event WHERE id=?",5L)).thenReturn(Map.of("id",5L,"title","X","description","","venue_text",""));
        assertEquals("5",service.createEvent(9,"{\"title\":\"X\"}").get("eventId"));
        verify(store).insert("INSERT INTO catalogue_mutation(event_id,event_session_id,created_at) VALUES(?,?,?)",5L,null,Timestamp.from(now));
        verify(store).insert(contains("catalogue_mutation_id"),eq(101L),contains("\"eventId\":\"5\""),eq(Timestamp.from(now)),eq(Timestamp.from(now)),eq(101L));
        var ordered=inOrder(manager,invalidator);ordered.verify(manager).commit(any());ordered.verify(invalidator).deliver(202L);
    }
}
