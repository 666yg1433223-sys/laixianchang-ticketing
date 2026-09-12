package com.ticketing.booking.product;

import com.ticketing.booking.config.Diagnostics;
import com.ticketing.booking.persistence.BookingStore;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("local-sim")
public class WaitlistScanner {
    private final BookingStore store;private final WaitlistService service;private final Diagnostics diagnostics;
    private long cursor;
    public WaitlistScanner(BookingStore store,WaitlistService service,Diagnostics diagnostics) { this.store=store;this.service=service;this.diagnostics=diagnostics; }
    @Scheduled(fixedDelay=1000,initialDelay=2000)
    public synchronized void scan() {
        try {
            var tiers=store.jdbc().queryForList("SELECT DISTINCT w.tier_id FROM waitlist_entry w JOIN ticket_tier t ON t.id=w.tier_id JOIN event_session s ON s.id=w.session_id WHERE w.status='WAITING' AND w.tier_id>? AND (s.booking_close_at<=UTC_TIMESTAMP(3) OR (s.state='OPEN' AND t.available>0)) ORDER BY w.tier_id LIMIT 20",Long.class,cursor);
            for(long tier:tiers) { try { service.advance(tier); } catch(RuntimeException e) { diagnostics.record(e); } }
            cursor=tiers.size()<20?0:tiers.get(tiers.size()-1);
        } catch(RuntimeException e) { diagnostics.record(e); }
    }
}
