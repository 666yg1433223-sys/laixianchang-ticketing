package com.ticketing.booking.service;

import com.ticketing.booking.config.RuntimeServices;
import com.ticketing.booking.persistence.BookingStore;
import static com.ticketing.booking.domain.Values.*;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AllocationService {
    private final BookingStore store;
    private final RuntimeServices runtime;
    private final TransactionTemplate tx;
    private final com.ticketing.booking.config.Diagnostics diagnostics;
    public final AtomicLong technicalFailures = new AtomicLong();
    public volatile String lastFailureType = "";
    private record Locked(Map<String,Object> session,Map<String,Object> tier,Map<String,Object> quota,
            Map<String,Object> request,Map<String,Object> order,Map<String,Object> event) { }
    private record Generation(int attempts,Instant due,long round) { }

    public AllocationService(BookingStore store,RuntimeServices runtime,PlatformTransactionManager manager,com.ticketing.booking.config.Diagnostics diagnostics) {
        this.store=store; this.runtime=runtime;
        this.diagnostics=diagnostics;
        tx=new TransactionTemplate(manager); tx.setIsolationLevel(2); tx.setTimeout(8);
    }

    public void process(long id) {
        Generation[] generation=new Generation[1];
        try {
            runtime.at("BEFORE_T2",id);
            tx.executeWithoutResult(status -> {
                Locked lock=lock(id);
                if (terminal(lock)) { return; }
                generation[0]=new Generation((int)number(lock.event(),"attempts"),instant(lock.event().get("next_attempt_at")),number(lock.event(),"recovery_generation"));
                runtime.at("T2_LOCKS_ACQUIRED",id);
                Instant now=runtime.afterLocks(store.jdbc());
                if (!now.isBefore(instant(lock.request().get("process_deadline")))) {
                    finish(lock,"EXPIRED","PROCESS_DEADLINE_EXCEEDED",null,now,"DONE"); return;
                }
                if (now.isBefore(generation[0].due())) { return; }
                if (!"OPEN".equals(lock.session().get("state")) || now.isBefore(instant(lock.session().get("booking_open_at")))
                        || !now.isBefore(instant(lock.session().get("booking_close_at")))) {
                    finish(lock,"REJECTED","SESSION_NOT_OPEN",null,now,"DONE"); return;
                }
                if (!"NONE".equals(lock.quota().get("owner_type"))) {
                    finish(lock,"REJECTED","QUOTA_ALREADY_HELD",null,now,"DONE"); return;
                }
                long tier=number(lock.tier(),"id");
                if (runtime.productEnabled() && store.jdbc().queryForObject("SELECT COUNT(*) FROM waitlist_entry WHERE tier_id=? AND status='WAITING'",Long.class,tier)>0) {
                    finish(lock,"REJECTED","WAITLIST_REQUIRED",null,now,"DONE"); return;
                }
                int changed=store.jdbc().update("UPDATE ticket_tier SET available=available-1 WHERE id=? AND available>0",tier);
                if (changed==0) { finish(lock,"REJECTED","NO_TICKETS",null,now,"DONE"); return; }
                one(changed);
                runtime.at("AFTER_STOCK",id);
                int balance=store.jdbc().queryForObject("SELECT available FROM ticket_tier WHERE id=?",Integer.class,tier);
                int price=(int)number(lock.tier(),"unit_price_cent");
                long user=number(lock.request(),"user_id"), session=number(lock.request(),"session_id");
                Instant expiry=earlier(now.plusSeconds(600),instant(lock.session().get("booking_close_at")));
                long order=store.insert("INSERT INTO booking_order(request_id,user_id,session_id,tier_id,status,unit_price_cent,quantity,payable_cent,currency,hold_created_at,pay_expires_at) VALUES(?,?,?,?,'HOLD',?,1,?,'CNY',?,?)",
                    id,user,session,tier,price,price,sqlTime(now),sqlTime(expiry));
                runtime.at("AFTER_ORDER",id);
                one(store.jdbc().update("UPDATE user_session_quota SET owner_type='ORDER',owner_id=?,updated_at=? WHERE user_id=? AND session_id=? AND owner_type='NONE' AND owner_id IS NULL",
                    order,sqlTime(now),user,session));
                store.insert("INSERT INTO inventory_ledger(tier_id,order_id,action,delta,available_after,created_at) VALUES(?,?,'RESERVE',-1,?,?)",tier,order,balance,sqlTime(now));
                runtime.at("AFTER_LEDGER",id);
                finish(lock,"SUCCEEDED",null,order,now,"DONE");
                runtime.at("T2_BEFORE_COMMIT",id);
            });
            runtime.at("T2_COMMITTED",id);
        } catch (RuntimeException failure) {
            diagnostics.record(failure);
            technicalFailures.incrementAndGet(); lastFailureType=failure.getClass().getSimpleName();
            // A commit may already have succeeded; terminal checks in the independent transaction win.
            if (generation[0]!=null) { recordFailure(id,generation[0]); }
        }
    }

    private Locked lock(long id) {
        var route=store.required("SELECT user_id,session_id,tier_id FROM booking_request WHERE id=?",id);
        long user=number(route,"user_id"),session=number(route,"session_id"),tier=number(route,"tier_id");
        var s=store.required("SELECT * FROM event_session WHERE id=? FOR SHARE",session);
        runtime.at("T2_SESSION_LOCKED",id);
        var t=store.required("SELECT * FROM ticket_tier WHERE id=? AND session_id=? FOR UPDATE",tier,session);
        store.jdbc().update("INSERT INTO user_session_quota(user_id,session_id,owner_type,owner_id,updated_at) VALUES(?,?,'NONE',NULL,UTC_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE user_id=user_id",user,session);
        var q=store.required("SELECT * FROM user_session_quota WHERE user_id=? AND session_id=? FOR UPDATE",user,session);
        var r=store.required("SELECT * FROM booking_request WHERE id=? FOR UPDATE",id);
        var o=store.optional("SELECT * FROM booking_order WHERE request_id=? FOR UPDATE",id);
        var e=store.required("SELECT * FROM outbox_event WHERE aggregate_id=? AND event_type='BOOKING_REQUESTED' FOR UPDATE",id);
        if (number(r,"user_id")!=user || number(r,"session_id")!=session || number(r,"tier_id")!=tier) { throw new IllegalStateException("IMMUTABLE_ROUTE_CHANGED"); }
        return new Locked(s,t,q,r,o,e);
    }
    private boolean terminal(Locked lock) {
        String state=(String)lock.request().get("status");
        String event=(String)lock.event().get("state");
        if ("ACCEPTED".equals(state)) {
            if (!("READY".equals(event) || (runtime.productEnabled() && "PUBLISHED".equals(event))) || lock.order()!=null) { throw new IllegalStateException("INCONSISTENT_ACCEPTED"); }
            return false;
        }
        if ("SUCCEEDED".equals(state)) {
            if (lock.order()==null || number(lock.order(),"id")!=number(lock.request(),"order_id") || !"DONE".equals(event)) { throw new IllegalStateException("INCONSISTENT_SUCCEEDED"); }
        } else if ("PROCESSING_FAILED".equals(state)) {
            if (!"DEAD".equals(event) || lock.order()!=null) { throw new IllegalStateException("INCONSISTENT_FAILED"); }
        } else if (!"DONE".equals(event) || lock.order()!=null) { throw new IllegalStateException("INCONSISTENT_TERMINAL"); }
        return true;
    }
    private void finish(Locked lock,String status,String reason,Long order,Instant now,String eventState) {
        one(store.jdbc().update("UPDATE booking_request SET status=?,reason_code=?,order_id=?,finished_at=? WHERE id=? AND status='ACCEPTED'",
            status,reason,order,sqlTime(now),number(lock.request(),"id")));
        one(store.jdbc().update("UPDATE outbox_event SET state=?,next_attempt_at=NULL,finished_at=? WHERE id=? AND state IN ('READY','PUBLISHED')",
            eventState,sqlTime(now),number(lock.event(),"id")));
    }
    private void recordFailure(long id,Generation original) {
        try {
            tx.executeWithoutResult(status -> {
                Locked lock=lock(id);
                if (terminal(lock)) { return; }
                Instant now=runtime.afterLocks(store.jdbc());
                Instant deadline=instant(lock.request().get("process_deadline"));
                if (!now.isBefore(deadline)) { finish(lock,"EXPIRED","PROCESS_DEADLINE_EXCEEDED",null,now,"DONE"); return; }
                int attempts=(int)number(lock.event(),"attempts");
                if (attempts!=original.attempts() || original.round()!=number(lock.event(),"recovery_generation") || !original.due().equals(instant(lock.event().get("next_attempt_at")))) { return; }
                int failures=attempts+1;
                one(store.jdbc().update("UPDATE outbox_event SET attempts=?,last_error_code='T2_TECHNICAL_FAILURE' WHERE id=? AND attempts=? AND state IN ('READY','PUBLISHED')",
                    failures,number(lock.event(),"id"),attempts));
                if (failures>=6) { finish(lock,"PROCESSING_FAILED","RETRY_EXHAUSTED",null,now,"DEAD"); return; }
                long base=1000L << (failures-1);
                Instant next=earlier(now.plusMillis(base+ThreadLocalRandom.current().nextLong(base/5+1)),deadline);
                one(store.jdbc().update("UPDATE outbox_event SET next_attempt_at=? WHERE id=? AND state IN ('READY','PUBLISHED')",sqlTime(next),number(lock.event(),"id")));
            });
        } catch (RuntimeException unavailable) {
            diagnostics.record(unavailable);
            technicalFailures.incrementAndGet(); lastFailureType=unavailable.getClass().getSimpleName();
            // No recursive retry. Durable ACCEPTED/READY is retried by the bounded scanner after recovery.
        }
    }
}
