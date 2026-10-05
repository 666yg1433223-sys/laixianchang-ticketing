package com.ticketing.booking.recovery;

import com.ticketing.booking.service.RefundService;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Durable due rows survive restarts; settlement safely tolerates concurrent scanner deliveries. */
@Component
@Profile("local-sim")
public class RefundScanner {
    private final RefundService service;
    public RefundScanner(RefundService service) { this.service=service; }
    @Scheduled(fixedDelay=1000,initialDelay=2000)
    public void scan() { service.scanOnce(); }
}
