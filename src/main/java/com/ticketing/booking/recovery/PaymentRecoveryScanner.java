package com.ticketing.booking.recovery;

import com.ticketing.booking.config.*;
import com.ticketing.booking.persistence.BookingStore;
import com.ticketing.booking.service.PaymentService;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import static com.ticketing.booking.domain.Values.*;

/** Two independent bounded cursors. Candidate selection takes no row locks. */
@Component
public class PaymentRecoveryScanner {
    private final BookingStore store; private final RuntimeServices runtime;
    private final PaymentService payment; private final Diagnostics diagnostics;
    private final long[] cursor={0,0}, upper={0,0};
    public PaymentRecoveryScanner(BookingStore store,RuntimeServices runtime,PaymentService payment,Diagnostics diagnostics) {
        this.store=store;this.runtime=runtime;this.payment=payment;this.diagnostics=diagnostics;
    }
    @Scheduled(fixedDelay=1000,initialDelay=1000)
    public void generateTick() {
        if(runtime.recoveryEnabled()) { generateOnce(); }
    }
    public void generateOnce() {
        if(!payment.enabled()) { return; }
        try { for(long id:candidates(0)) { try { payment.generate(id); } catch(RuntimeException e) { diagnostics.record(e); } } }
        catch(RuntimeException e) { diagnostics.record(e); }
    }
    @Scheduled(fixedDelay=1000,initialDelay=1000)
    public void settleTick() {
        if(runtime.recoveryEnabled()) { settleOnce(); }
    }
    public void settleOnce() {
        try { for(long id:candidates(1)) { payment.settle(id); } }
        catch(RuntimeException e) { diagnostics.record(e); }
    }
    private synchronized List<Long> candidates(int phase) {
        if(cursor[phase]==0) { upper[phase]=store.jdbc().queryForObject("SELECT COALESCE(MAX(id),0) FROM payment_attempt",Long.class); }
        var now=sqlTime(runtime.afterLocks(store.jdbc()));
        List<Long> ids=phase==0
            ? store.jdbc().queryForList("SELECT id FROM payment_attempt WHERE id>? AND id<=? AND result_status='PENDING' AND (planned_at<=? OR resolution_deadline<=?) ORDER BY id LIMIT 20",Long.class,cursor[phase],upper[phase],now,now)
            : store.jdbc().queryForList("SELECT a.id FROM payment_attempt a JOIN outbox_event e ON e.payment_attempt_id=a.id AND e.event_type='PAYMENT_RESULT_RECORDED' WHERE a.id>? AND a.id<=? AND e.state='READY' AND e.next_attempt_at<=? ORDER BY a.id LIMIT 20",Long.class,cursor[phase],upper[phase],now);
        cursor[phase]=ids.size()<20?0:ids.get(ids.size()-1);return ids;
    }
}
