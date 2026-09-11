package com.ticketing.booking.recovery;

import com.ticketing.booking.config.RuntimeServices;
import com.ticketing.booking.persistence.BookingStore;
import com.ticketing.booking.service.AllocationService;
import static com.ticketing.booking.domain.Values.sqlTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
public class RecoveryScanner {
    private final BookingStore store;
    private final RuntimeServices runtime;
    private final AllocationService allocation;
    private final com.ticketing.booking.config.Diagnostics diagnostics;
    private long cursor;
    private long sweepUpper;
    public final AtomicLong failedScans=new AtomicLong();
    public RecoveryScanner(BookingStore store,RuntimeServices runtime,AllocationService allocation,com.ticketing.booking.config.Diagnostics diagnostics) {
        this.store=store; this.runtime=runtime; this.allocation=allocation; this.diagnostics=diagnostics;
    }
    @Scheduled(fixedDelay=1000,initialDelay=1000)
    public void scheduled() { if (runtime.recoveryEnabled()) { scanOnce(); } }
    public void scanOnce() {
        try {
            for (long id : candidates()) {
                allocation.process(id);
            }
        } catch (RuntimeException failure) {
            failedScans.incrementAndGet();
            diagnostics.record(new IllegalStateException("BOOKING_SCAN_FAILED",failure));
        }
    }
    private synchronized List<Long> candidates() {
        if (cursor==0) {
            sweepUpper=store.jdbc().queryForObject("SELECT COALESCE(MAX(id),0) FROM booking_request",Long.class);
        }
        var now=sqlTime(runtime.afterLocks(store.jdbc()));
        var ids=store.jdbc().queryForList("SELECT r.id FROM booking_request r WHERE r.status='ACCEPTED' AND r.id>? AND r.id<=? AND (r.process_deadline<=? OR EXISTS (SELECT 1 FROM outbox_event e WHERE e.aggregate_id=r.id AND e.event_type='BOOKING_REQUESTED' AND e.state='READY' AND e.next_attempt_at<=?)) ORDER BY r.id LIMIT 20",
            Long.class,cursor,sweepUpper,now,now);
        cursor=ids.size()<20?0:ids.get(ids.size()-1);
        return ids;
    }
}
