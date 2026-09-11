package com.ticketing.booking.recovery;

import com.ticketing.booking.config.RuntimeServices;
import com.ticketing.booking.persistence.BookingStore;
import com.ticketing.booking.service.AllocationService;
import static com.ticketing.booking.domain.Values.sqlTime;
import java.util.LinkedHashSet;
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
    public final AtomicLong failedScans=new AtomicLong();
    public RecoveryScanner(BookingStore store,RuntimeServices runtime,AllocationService allocation) {
        this.store=store; this.runtime=runtime; this.allocation=allocation;
    }
    @Scheduled(fixedDelay=1000,initialDelay=1000)
    public void scheduled() { if (runtime.recoveryEnabled()) { scanOnce(); } }
    public void scanOnce() {
        try {
            var now=sqlTime(runtime.afterLocks(store.jdbc()));
            LinkedHashSet<Long> candidates=new LinkedHashSet<>(store.jdbc().queryForList(
                "SELECT id FROM booking_request WHERE status='ACCEPTED' AND process_deadline<=? ORDER BY process_deadline,id LIMIT 20",Long.class,now));
            candidates.addAll(store.jdbc().queryForList(
                "SELECT aggregate_id FROM outbox_event WHERE state='READY' AND next_attempt_at<=? ORDER BY next_attempt_at,id LIMIT 20",Long.class,now));
            candidates.stream().limit(20).forEach(allocation::process);
        } catch (RuntimeException failure) { failedScans.incrementAndGet(); }
    }
}
