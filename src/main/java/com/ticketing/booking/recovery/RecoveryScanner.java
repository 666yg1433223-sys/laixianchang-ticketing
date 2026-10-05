package com.ticketing.booking.recovery;

import com.ticketing.booking.config.RuntimeServices;
import com.ticketing.booking.repository.RecoveryRepository;
import com.ticketing.booking.repository.DatabaseTimeRepository;
import com.ticketing.booking.service.WaitlistService;
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
    private final RecoveryRepository repository;
    private final RuntimeServices runtime;
    private final DatabaseTimeRepository clock;
    private final AllocationService allocation;
    private final com.ticketing.booking.config.Diagnostics diagnostics;
    private long cursor;
    private long sweepUpper;
    public final AtomicLong failedScans=new AtomicLong();
    public RecoveryScanner(RecoveryRepository repository,DatabaseTimeRepository clock,RuntimeServices runtime,AllocationService allocation,com.ticketing.booking.config.Diagnostics diagnostics) {
        this.repository=repository;this.clock=clock; this.runtime=runtime; this.allocation=allocation; this.diagnostics=diagnostics;
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
            sweepUpper=repository.latestBookingRequestId();
        }
        var now=sqlTime(runtime.afterLocks(clock));
        var ids=repository.bookingCandidates(cursor,sweepUpper,now);
        cursor=ids.size()<20?0:ids.get(ids.size()-1);
        return ids;
    }
}
