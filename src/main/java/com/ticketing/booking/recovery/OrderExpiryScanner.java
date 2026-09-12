package com.ticketing.booking.recovery;

import com.ticketing.booking.config.Diagnostics;
import com.ticketing.booking.config.RuntimeServices;
import com.ticketing.booking.persistence.BookingStore;
import com.ticketing.booking.service.OrderCloseService;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import static com.ticketing.booking.domain.Values.sqlTime;

@Component
public class OrderExpiryScanner {
    private final BookingStore store;
    private final RuntimeServices runtime;
    private final OrderCloseService close;
    private final Diagnostics diagnostics;
    private long cursor;
    private long sweepUpper;
    public final AtomicLong failedCloses = new AtomicLong();

    public OrderExpiryScanner(BookingStore store, RuntimeServices runtime, OrderCloseService close, Diagnostics diagnostics) {
        this.store = store; this.runtime = runtime; this.close = close; this.diagnostics = diagnostics;
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
            sweepUpper = store.jdbc().queryForObject("SELECT COALESCE(MAX(id),0) FROM booking_order", Long.class);
        }
        var now = sqlTime(runtime.afterLocks(store.jdbc()));
        var ids = store.jdbc().queryForList("SELECT id FROM booking_order WHERE status='HOLD' AND pay_expires_at<=? AND id>? AND id<=? ORDER BY id LIMIT 20",
                Long.class, now, cursor, sweepUpper);
        cursor = ids.size() < 20 ? 0 : ids.get(ids.size() - 1);
        return ids;
    }
}
