package com.ticketing.booking.recovery;

import com.ticketing.booking.config.Diagnostics;
import com.ticketing.booking.config.RuntimeServices;
import com.ticketing.booking.repository.RecoveryRepository;
import com.ticketing.booking.repository.DatabaseTimeRepository;
import com.ticketing.booking.service.WaitlistService;
import com.ticketing.booking.service.OrderCloseService;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import static com.ticketing.booking.domain.Values.sqlTime;

@Component
public class OrderExpiryScanner {
    private final RecoveryRepository repository;
    private final RuntimeServices runtime;
    private final DatabaseTimeRepository clock;
    private final OrderCloseService close;
    private final Diagnostics diagnostics;
    private long cursor;
    private long sweepUpper;
    public final AtomicLong failedCloses = new AtomicLong();

    public OrderExpiryScanner(RecoveryRepository repository,DatabaseTimeRepository clock, RuntimeServices runtime, OrderCloseService close, Diagnostics diagnostics) {
        this.repository = repository; this.clock=clock; this.runtime = runtime; this.close = close; this.diagnostics = diagnostics;
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 1000)
    public void scheduled() { if (runtime.settlementRecoveryEnabled()) { scanOnce(); } }

    public void scanOnce() {
        try {
            for (long id : candidates()) {
                try { close.expire(id); }
                catch (RuntimeException failure) {
                    failedCloses.incrementAndGet();
                    diagnostics.record(new IllegalStateException("EXPIRY_CLOSE_FAILED orderId=" + id, failure));
                }
            }
        } catch (RuntimeException failure) {
            failedCloses.incrementAndGet();
            diagnostics.record(new IllegalStateException("EXPIRY_SCAN_FAILED", failure));
        }
    }

    // No DB locks across candidate selection or across orders. Freeze the upper ID per sweep:
    // a permanently bad first page cannot starve later IDs, nor can incoming IDs prevent wrap-around.
    private synchronized List<Long> candidates() {
        if (cursor == 0) {
            sweepUpper = repository.latestOrderId();
        }
        var now = sqlTime(runtime.afterLocks(clock));
        var ids = repository.expiredOrderCandidates(cursor,sweepUpper,now);
        cursor = ids.size() < 20 ? 0 : ids.get(ids.size() - 1);
        return ids;
    }
}
