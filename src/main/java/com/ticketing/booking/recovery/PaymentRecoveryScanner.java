package com.ticketing.booking.recovery;

import com.ticketing.booking.config.*;
import com.ticketing.booking.repository.RecoveryRepository;
import com.ticketing.booking.repository.DatabaseTimeRepository;
import com.ticketing.booking.service.WaitlistService;
import com.ticketing.booking.service.PaymentService;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import static com.ticketing.booking.domain.Values.*;

/** Two independent bounded cursors. Candidate selection takes no row locks. */
@Component
public class PaymentRecoveryScanner {
    private final RecoveryRepository repository; private final RuntimeServices runtime;
    private final DatabaseTimeRepository clock;
    private final PaymentService payment; private final Diagnostics diagnostics;
    private final long[] cursor={0,0}, upper={0,0};
    public PaymentRecoveryScanner(RecoveryRepository repository,DatabaseTimeRepository clock,RuntimeServices runtime,PaymentService payment,Diagnostics diagnostics) {
        this.repository=repository;this.clock=clock;this.runtime=runtime;this.payment=payment;this.diagnostics=diagnostics;
    }
    @Scheduled(fixedDelay=1000,initialDelay=1000)
    public void generateTick() {
        if(runtime.settlementRecoveryEnabled()) { generateOnce(); }
    }
    public void generateOnce() {
        if(!payment.enabled()) { return; }
        try { for(long id:candidates(0)) { try { payment.generate(id); } catch(RuntimeException e) { diagnostics.record(e); } } }
        catch(RuntimeException e) { diagnostics.record(e); }
    }
    @Scheduled(fixedDelay=1000,initialDelay=1000)
    public void settleTick() {
        if(runtime.settlementRecoveryEnabled()) { settleOnce(); }
    }
    public void settleOnce() {
        try { for(long id:candidates(1)) { payment.settle(id); } }
        catch(RuntimeException e) { diagnostics.record(e); }
    }
    private synchronized List<Long> candidates(int phase) {
        if(cursor[phase]==0) { upper[phase]=repository.latestPaymentAttemptId(); }
        var now=sqlTime(runtime.afterLocks(clock));
        List<Long> ids=phase==0
            ? repository.paymentGenerationCandidates(cursor[phase],upper[phase],now)
            : repository.paymentSettlementCandidates(cursor[phase],upper[phase],now);
        cursor[phase]=ids.size()<20?0:ids.get(ids.size()-1);return ids;
    }
}
