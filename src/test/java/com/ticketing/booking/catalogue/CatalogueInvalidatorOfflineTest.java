package com.ticketing.booking.catalogue;

import com.ticketing.booking.config.Diagnostics;
import com.ticketing.booking.config.RuntimeServices;
import com.ticketing.booking.domain.DatabaseTimeSource;
import com.ticketing.booking.model.po.CatalogueInvalidationPO;
import com.ticketing.booking.repository.CatalogueRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CatalogueInvalidatorOfflineTest {
    private final CatalogueRepository catalogue = mock(CatalogueRepository.class);
    private final CatalogueCache cache = mock(CatalogueCache.class);
    private final RuntimeServices runtime = mock(RuntimeServices.class);
    private final DatabaseTimeSource clock = mock(DatabaseTimeSource.class);
    private final Diagnostics diagnostics = mock(Diagnostics.class);
    private final CatalogueInvalidator invalidator = new CatalogueInvalidator(catalogue, cache, runtime, clock, diagnostics);
    private final Instant now = Instant.parse("2026-10-05T00:00:00Z");

    @Test void successfulDeliveryUsesTheReadRecoveryGeneration() {
        when(catalogue.findReadyInvalidation(10L)).thenReturn(new CatalogueInvalidationPO(10L, 2L, 7L, 5L, 9L));
        invalidator.deliver(10L);
        var order = inOrder(cache, catalogue);
        order.verify(catalogue).findReadyInvalidation(10L);
        order.verify(cache).invalidate(5L, 9L);
        order.verify(catalogue).completeInvalidation(10L, 7L);
        verifyNoInteractions(runtime, diagnostics);
    }

    @Test void failedDeletionBacksOffWithoutLosingTheAttemptAndGenerationGuards() {
        when(catalogue.findReadyInvalidation(10L)).thenReturn(new CatalogueInvalidationPO(10L, 2L, 7L, 5L, null));
        when(runtime.afterLocks(clock)).thenReturn(now);
        var failure = new IllegalStateException("cache unavailable");
        doThrow(failure).when(cache).invalidate(5L, null);
        invalidator.deliver(10L);
        verify(catalogue).recordInvalidationFailure(10L, 2L, 7L, 3L, "READY", now.plusSeconds(8), null);
        verify(catalogue, never()).completeInvalidation(anyLong(), anyLong());
        verify(diagnostics).record(failure);
    }

    @Test void sixthFailureStopsAutomaticRetriesAndPreservesFinishTime() {
        when(catalogue.findReadyInvalidation(10L)).thenReturn(new CatalogueInvalidationPO(10L, 5L, 7L, 5L, 9L));
        when(runtime.afterLocks(clock)).thenReturn(now);
        doThrow(new IllegalStateException("cache unavailable")).when(cache).invalidate(5L, 9L);
        invalidator.deliver(10L);
        verify(catalogue).recordInvalidationFailure(10L, 5L, 7L, 6L, "DEAD", null, now);
    }

    @Test void noLongerReadyDeliveryDoesNotInvalidateOrUpdateAnything() {
        invalidator.deliver(10L);
        verify(catalogue).findReadyInvalidation(10L);
        verifyNoMoreInteractions(catalogue);
        verifyNoInteractions(cache, runtime, diagnostics);
    }
}
