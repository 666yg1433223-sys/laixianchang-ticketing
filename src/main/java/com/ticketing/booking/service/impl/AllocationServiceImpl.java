package com.ticketing.booking.service.impl;

import com.ticketing.booking.config.RuntimeServices;
import com.ticketing.booking.config.Diagnostics;
import com.ticketing.booking.model.po.BookingRows.*;
import com.ticketing.booking.repository.AllocationRepository;
import com.ticketing.booking.repository.DatabaseTimeRepository;
import com.ticketing.booking.service.AllocationService;
import static com.ticketing.booking.domain.Values.*;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AllocationServiceImpl implements AllocationService {
    private final AllocationRepository repository;
    private final DatabaseTimeRepository clock;
    private final RuntimeServices runtime;
    private final TransactionTemplate tx;
    private final Diagnostics diagnostics;
    private final AtomicLong technicalFailures=new AtomicLong();
    private volatile String lastFailureType="";
    private record Locked(Session session,Tier tier,Quota quota,Request request,Order order,Outbox event) { }
    private record Generation(int attempts,Instant due,long round) { }
    public AllocationServiceImpl(AllocationRepository repository,DatabaseTimeRepository clock,RuntimeServices runtime,
            PlatformTransactionManager manager,Diagnostics diagnostics) {
        this.repository=repository;this.clock=clock;this.runtime=runtime;this.diagnostics=diagnostics;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(2);tx.setTimeout(8);
    }
    @Override public long technicalFailures() { return technicalFailures.get(); }
    @Override public String lastFailureType() { return lastFailureType; }
    @Override public void process(long id) {
        Generation[] generation=new Generation[1];
        try {
            runtime.at("BEFORE_T2",id);
            tx.executeWithoutResult(status->{
                Locked lock=lock(id);if(terminal(lock)) { return; }
                generation[0]=new Generation(lock.event().attempts(),lock.event().nextAttemptAt(),lock.event().recoveryGeneration());
                runtime.at("T2_LOCKS_ACQUIRED",id);Instant now=runtime.afterLocks(clock);
                if(!now.isBefore(lock.request().processDeadline())) { finish(lock,"EXPIRED","PROCESS_DEADLINE_EXCEEDED",null,now,"DONE");return; }
                if(now.isBefore(generation[0].due())) { return; }
                if(!"OPEN".equals(lock.session().state()) || now.isBefore(lock.session().opensAt()) || !now.isBefore(lock.session().closesAt())) {
                    finish(lock,"REJECTED","SESSION_NOT_OPEN",null,now,"DONE");return;
                }
                if(!"NONE".equals(lock.quota().ownerType())) { finish(lock,"REJECTED","QUOTA_ALREADY_HELD",null,now,"DONE");return; }
                long tier=lock.tier().id();
                if(runtime.productEnabled() && repository.waitingCount(tier)>0) { finish(lock,"REJECTED","WAITLIST_REQUIRED",null,now,"DONE");return; }
                int changed=repository.reserveTicket(tier);
                if(changed==0) { finish(lock,"REJECTED","NO_TICKETS",null,now,"DONE");return; }one(changed);
                runtime.at("AFTER_STOCK",id);int balance=repository.available(tier),price=lock.tier().unitPriceCent();
                long user=lock.request().userId(),session=lock.request().sessionId();
                Instant expiry=earlier(now.plusSeconds(600),lock.session().closesAt());
                long order=repository.insertOrder(id,user,session,tier,price,now,expiry);
                runtime.at("AFTER_ORDER",id);one(repository.claimOrderQuota(order,now,user,session));
                repository.insertReservation(tier,order,balance,now);runtime.at("AFTER_LEDGER",id);
                finish(lock,"SUCCEEDED",null,order,now,"DONE");runtime.at("T2_BEFORE_COMMIT",id);
            });
            runtime.at("T2_COMMITTED",id);
        } catch(RuntimeException failure) {
            diagnostics.record(failure);technicalFailures.incrementAndGet();lastFailureType=failure.getClass().getSimpleName();
            // Independent transaction rechecks durable terminal facts after uncertain commits.
            if(generation[0]!=null) { recordFailure(id,generation[0]); }
        }
    }
    private Locked lock(long id) {
        var route=repository.requestRoute(id);long user=route.userId(),session=route.sessionId(),tier=route.tierId();
        var s=repository.lockSession(session);runtime.at("T2_SESSION_LOCKED",id);
        var t=repository.lockTier(tier,session);repository.ensureQuota(user,session);
        var q=repository.lockQuota(user,session);var r=repository.lockRequest(id);
        var o=repository.lockRequestOrder(id);var e=repository.lockRequestedEvent(id);
        if(r.userId()!=user || r.sessionId()!=session || r.tierId()!=tier) { throw new IllegalStateException("IMMUTABLE_ROUTE_CHANGED"); }
        return new Locked(s,t,q,r,o,e);
    }
    private boolean terminal(Locked lock) {
        String state=lock.request().status(),event=lock.event().state();
        if("ACCEPTED".equals(state)) {
            if(!("READY".equals(event) || (runtime.productEnabled() && "PUBLISHED".equals(event))) || lock.order()!=null) { throw new IllegalStateException("INCONSISTENT_ACCEPTED"); }return false;
        }
        if("SUCCEEDED".equals(state)) {
            if(lock.order()==null || lock.request().orderId()==null || lock.order().id()!=lock.request().orderId() || !"DONE".equals(event)) { throw new IllegalStateException("INCONSISTENT_SUCCEEDED"); }
        } else if("PROCESSING_FAILED".equals(state)) {
            if(!"DEAD".equals(event) || lock.order()!=null) { throw new IllegalStateException("INCONSISTENT_FAILED"); }
        } else if(!"DONE".equals(event) || lock.order()!=null) { throw new IllegalStateException("INCONSISTENT_TERMINAL"); }
        return true;
    }
    private void finish(Locked lock,String status,String reason,Long order,Instant now,String eventState) {
        one(repository.finishRequest(lock.request().id(),status,reason,order,now));one(repository.finishEvent(lock.event().id(),eventState,now));
    }
    private void recordFailure(long id,Generation original) {
        try {
            tx.executeWithoutResult(status->{
                Locked lock=lock(id);if(terminal(lock)) { return; }
                Instant now=runtime.afterLocks(clock),deadline=lock.request().processDeadline();
                if(!now.isBefore(deadline)) { finish(lock,"EXPIRED","PROCESS_DEADLINE_EXCEEDED",null,now,"DONE");return; }
                int attempts=lock.event().attempts();
                if(attempts!=original.attempts() || original.round()!=lock.event().recoveryGeneration() || !original.due().equals(lock.event().nextAttemptAt())) { return; }
                int failures=attempts+1;one(repository.recordFailure(lock.event().id(),failures,attempts));
                if(failures>=6) { finish(lock,"PROCESSING_FAILED","RETRY_EXHAUSTED",null,now,"DEAD");return; }
                long base=1000L<<(failures-1);Instant next=earlier(now.plusMillis(base+ThreadLocalRandom.current().nextLong(base/5+1)),deadline);
                one(repository.scheduleRetry(lock.event().id(),next));
            });
        } catch(RuntimeException unavailable) {
            diagnostics.record(unavailable);technicalFailures.incrementAndGet();lastFailureType=unavailable.getClass().getSimpleName();
        }
    }
}
