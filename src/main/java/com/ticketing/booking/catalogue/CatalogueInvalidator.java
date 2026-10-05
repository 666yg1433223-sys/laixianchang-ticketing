package com.ticketing.booking.catalogue;

import com.ticketing.booking.config.Diagnostics;
import com.ticketing.booking.config.RuntimeServices;
import com.ticketing.booking.domain.DatabaseTimeSource;
import com.ticketing.booking.repository.CatalogueRepository;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** A separate, durable lane: catalogue events never enter booking/payment consumers. */
@Component
@Profile("local-sim")
public class CatalogueInvalidator {
    private final CatalogueRepository catalogue; private final CatalogueCache cache; private final RuntimeServices runtime;
    private final DatabaseTimeSource clock; private final Diagnostics diagnostics;
    public CatalogueInvalidator(CatalogueRepository catalogue,CatalogueCache cache,RuntimeServices runtime,
                                DatabaseTimeSource clock,Diagnostics diagnostics) {
        this.catalogue=catalogue;this.cache=cache;this.runtime=runtime;this.clock=clock;this.diagnostics=diagnostics;
    }
    @Scheduled(fixedDelay=2000,initialDelay=2000)
    public void tick() {
        try {
            for(long id:catalogue.findDueInvalidations()) { deliver(id); }
        } catch(RuntimeException failure) { diagnostics.record(failure); }
    }
    public void deliver(long outboxId) {
        try {
            var source=catalogue.findReadyInvalidation(outboxId);
            if(source==null) { return; }
            long generation=source.recoveryGeneration(),attempts=source.attempts();
            try {
                cache.invalidate(source.eventId(),source.sessionId());
                catalogue.completeInvalidation(outboxId,generation);
            } catch(RuntimeException failure) {
                Instant now=runtime.afterLocks(clock); long next=attempts+1;
                catalogue.recordInvalidationFailure(outboxId,attempts,generation,next,
                    next>=6?"DEAD":"READY",next>=6?null:now.plusSeconds(Math.min(60,1L<<next)),next>=6?now:null);
                diagnostics.record(failure);
            }
        } catch(RuntimeException failure) { diagnostics.record(failure); }
    }
}
