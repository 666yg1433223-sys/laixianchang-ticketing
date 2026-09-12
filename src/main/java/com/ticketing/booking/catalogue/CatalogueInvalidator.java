package com.ticketing.booking.catalogue;

import com.ticketing.booking.config.Diagnostics;
import com.ticketing.booking.config.RuntimeServices;
import com.ticketing.booking.persistence.BookingStore;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import static com.ticketing.booking.domain.Values.*;

/** A separate, durable lane: catalogue events never enter booking/payment consumers. */
@Component
@Profile("local-sim")
public class CatalogueInvalidator {
    private final BookingStore store; private final CatalogueCache cache; private final RuntimeServices runtime; private final Diagnostics diagnostics;
    public CatalogueInvalidator(BookingStore store,CatalogueCache cache,RuntimeServices runtime,Diagnostics diagnostics) {
        this.store=store;this.cache=cache;this.runtime=runtime;this.diagnostics=diagnostics;
    }
    @Scheduled(fixedDelay=2000,initialDelay=2000)
    public void tick() {
        try {
            for(long id:store.jdbc().queryForList("SELECT id FROM outbox_event WHERE event_type='CATALOG_INVALIDATE' AND state='READY' AND next_attempt_at<=CURRENT_TIMESTAMP(3) ORDER BY id LIMIT 20",Long.class)) { deliver(id); }
        } catch(RuntimeException failure) { diagnostics.record(failure); }
    }
    public void deliver(long outboxId) {
        try {
            var source=store.optional("SELECT e.id,e.attempts,e.recovery_generation,m.event_id,m.event_session_id FROM outbox_event e JOIN catalogue_mutation m ON m.id=e.catalogue_mutation_id AND m.id=e.aggregate_id WHERE e.id=? AND e.event_type='CATALOG_INVALIDATE' AND e.schema_version=1 AND e.state='READY'",outboxId);
            if(source==null) { return; }
            long generation=number(source,"recovery_generation"),attempts=number(source,"attempts");
            try {
                cache.invalidate(number(source,"event_id"),source.get("event_session_id")==null?null:number(source,"event_session_id"));
                store.jdbc().update("UPDATE outbox_event SET state='DONE',next_attempt_at=NULL,last_error_code=NULL,finished_at=CURRENT_TIMESTAMP(3) WHERE id=? AND event_type='CATALOG_INVALIDATE' AND state='READY' AND recovery_generation=?",outboxId,generation);
            } catch(RuntimeException failure) {
                Instant now=runtime.afterLocks(store.jdbc()); long next=attempts+1;
                store.jdbc().update("UPDATE outbox_event SET attempts=?,state=?,next_attempt_at=?,finished_at=?,last_error_code='CACHE_DELETE_FAILED' WHERE id=? AND event_type='CATALOG_INVALIDATE' AND state='READY' AND attempts=? AND recovery_generation=?",
                    next,next>=6?"DEAD":"READY",next>=6?null:sqlTime(now.plusSeconds(Math.min(60,1L<<next))),next>=6?sqlTime(now):null,outboxId,attempts,generation);
                diagnostics.record(failure);
            }
        } catch(RuntimeException failure) { diagnostics.record(failure); }
    }
}
