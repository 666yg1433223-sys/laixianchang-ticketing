package com.ticketing.booking.recovery;

import com.ticketing.booking.config.Diagnostics;
import com.ticketing.booking.repository.RecoveryRepository;
import com.ticketing.booking.repository.DatabaseTimeRepository;
import com.ticketing.booking.service.WaitlistService;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("local-sim")
public class WaitlistScanner {
    private final RecoveryRepository repository;private final WaitlistService service;private final Diagnostics diagnostics;
    private long cursor;
    public WaitlistScanner(RecoveryRepository repository,WaitlistService service,Diagnostics diagnostics) { this.repository=repository;this.service=service;this.diagnostics=diagnostics; }
    @Scheduled(fixedDelay=1000,initialDelay=2000)
    public synchronized void scan() {
        try {
            var tiers=repository.waitingTierCandidates(cursor);
            for(long tier:tiers) { try { service.advance(tier); } catch(RuntimeException e) { diagnostics.record(e); } }
            cursor=tiers.size()<20?0:tiers.get(tiers.size()-1);
        } catch(RuntimeException e) { diagnostics.record(e); }
    }
}
