package com.ticketing.booking.product;

import com.ticketing.booking.config.RuntimeServices;
import com.ticketing.booking.domain.ApiFailure;
import com.ticketing.booking.persistence.BookingStore;
import com.ticketing.booking.service.EntryLimiter;
import static com.ticketing.booking.domain.Values.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** One ticket, one tier, committed FIFO. No Redis inventory and no automatic payment. */
@Service
@Profile("local-sim")
public class WaitlistService {
    private final BookingStore store;
    private final RuntimeServices runtime;
    private final EntryLimiter limiter;
    private final TransactionTemplate tx;
    public WaitlistService(BookingStore store,RuntimeServices runtime,EntryLimiter limiter,PlatformTransactionManager manager) {
        this.store=store;this.runtime=runtime;this.limiter=limiter;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(2);tx.setTimeout(8);
    }
    public Map<String,Object> join(long user,long session,long tier,String key) {
        ProductInput.key(key);byte[] hash=hash(session,tier);
        var existing=prior(user,key,hash);if(existing!=null) { return existing; }
        limiter.check(user,tier,runtime.afterLocks(store.jdbc()));
        return tx.execute(status -> {
            var s=store.optional("SELECT * FROM event_session WHERE id=? FOR SHARE",session);
            var t=store.optional("SELECT * FROM ticket_tier WHERE id=? AND session_id=? FOR UPDATE",tier,session);
            if(s==null || t==null) { throw new ApiFailure(404,"RESOURCE_NOT_FOUND"); }
            ensureQuota(user,session);
            var q=store.required("SELECT * FROM user_session_quota WHERE user_id=? AND session_id=? FOR UPDATE",user,session);
            var repeat=prior(user,key,hash);if(repeat!=null) { return repeat; }
            Instant now=runtime.afterLocks(store.jdbc());
            if(!open(s,now)) { throw new ApiFailure(409,"SESSION_NOT_OPEN"); }
            if(!"NONE".equals(q.get("owner_type"))) { throw new ApiFailure(409,"QUOTA_ALREADY_HELD"); }
            if(number(t,"available")>0 && head(tier)==null) { throw new ApiFailure(409,"TICKETS_AVAILABLE"); }
            long id=store.insert("INSERT INTO waitlist_entry(user_id,session_id,tier_id,idempotency_key,payload_hash,created_at) VALUES(?,?,?,?,?,?)",
                user,session,tier,key,hash,sqlTime(now));
            one(store.jdbc().update("UPDATE user_session_quota SET owner_type='WAITLIST',owner_id=?,updated_at=? WHERE user_id=? AND session_id=? AND owner_type='NONE'",id,sqlTime(now),user,session));
            return query(user,id);
        });
    }
    private Map<String,Object> prior(long user,String key,byte[] hash) {
        var row=store.optional("SELECT * FROM waitlist_entry WHERE user_id=? AND idempotency_key=?",user,key);
        if(row==null) { return null; }
        if(!MessageDigest.isEqual(hash,(byte[])row.get("payload_hash"))) { throw new ApiFailure(409,"IDEMPOTENCY_CONFLICT"); }
        return view(row);
    }
    public Map<String,Object> query(long user,long id) {
        var row=store.optional("SELECT * FROM waitlist_entry WHERE id=? AND user_id=?",id,user);
        if(row==null) { throw new ApiFailure(404,"RESOURCE_NOT_FOUND"); }
        return view(row);
    }
    public Map<String,Object> list(long user,int page,int size) {
        int offset=ProductInput.page(page,size);
        var rows=store.jdbc().queryForList("SELECT * FROM waitlist_entry WHERE user_id=? ORDER BY id DESC LIMIT ? OFFSET ?",user,size+1,offset);
        return Map.of("items",rows.stream().limit(size).map(this::view).toList(),"page",page,"size",size,"hasNext",rows.size()>size);
    }
    public Map<String,Object> cancel(long user,long id) {
        query(user,id);
        return tx.execute(status -> {
            var route=store.required("SELECT * FROM waitlist_entry WHERE id=?",id);
            long session=number(route,"session_id"),tier=number(route,"tier_id");
            var s=store.required("SELECT * FROM event_session WHERE id=? FOR SHARE",session);
            store.required("SELECT id FROM ticket_tier WHERE id=? FOR UPDATE",tier);
            var q=store.required("SELECT * FROM user_session_quota WHERE user_id=? AND session_id=? FOR UPDATE",user,session);
            var w=store.required("SELECT * FROM waitlist_entry WHERE id=? FOR UPDATE",id);
            if("ALLOCATED".equals(w.get("status"))) { throw new ApiFailure(409,"WAITLIST_ALREADY_ALLOCATED"); }
            if(!"WAITING".equals(w.get("status"))) { return view(w); }
            checkOwner(q,w);Instant now=runtime.afterLocks(store.jdbc());
            String end=now.isBefore(instant(s.get("booking_close_at")))?"CANCELLED":"EXPIRED";
            release(w,end,now);return query(user,id);
        });
    }
    public void advance(long tier) {
        tx.executeWithoutResult(status -> {
            var route=store.required("SELECT session_id FROM ticket_tier WHERE id=?",tier);
            long session=number(route,"session_id");
            var s=store.required("SELECT * FROM event_session WHERE id=? FOR SHARE",session);
            var t=store.required("SELECT * FROM ticket_tier WHERE id=? FOR UPDATE",tier);
            var head=head(tier);if(head==null) { return; }
            long id=number(head,"id"),user=number(head,"user_id");
            var q=store.required("SELECT * FROM user_session_quota WHERE user_id=? AND session_id=? FOR UPDATE",user,session);
            var w=store.required("SELECT * FROM waitlist_entry WHERE id=? FOR UPDATE",id);
            checkOwner(q,w);Instant now=runtime.afterLocks(store.jdbc());
            if(!now.isBefore(instant(s.get("booking_close_at")))) { release(w,"EXPIRED",now);return; }
            if(!open(s,now) || number(t,"available")==0) { return; }
            one(store.jdbc().update("UPDATE ticket_tier SET available=available-1 WHERE id=? AND available>0",tier));
            int price=(int)number(t,"unit_price_cent");Instant deadline=earlier(now.plusSeconds(600),instant(s.get("booking_close_at")));
            long order=store.insert("INSERT INTO booking_order(waitlist_entry_id,user_id,session_id,tier_id,status,unit_price_cent,payable_cent,hold_created_at,pay_expires_at) VALUES(?,?,?,?,'HOLD',?,?,?,?)",
                id,user,session,tier,price,price,sqlTime(now),sqlTime(deadline));
            one(store.jdbc().update("UPDATE user_session_quota SET owner_type='ORDER',owner_id=?,updated_at=? WHERE user_id=? AND session_id=? AND owner_type='WAITLIST' AND owner_id=?",order,sqlTime(now),user,session,id));
            one(store.jdbc().update("UPDATE waitlist_entry SET status='ALLOCATED',order_id=?,finished_at=? WHERE id=? AND status='WAITING'",order,sqlTime(now),id));
            store.insert("INSERT INTO inventory_ledger(tier_id,order_id,action,delta,available_after,created_at) VALUES(?,?,'RESERVE',-1,?,?)",tier,order,number(t,"available")-1,sqlTime(now));
            runtime.at("WAITLIST_BEFORE_COMMIT",id);
        });
    }
    private void release(Map<String,Object> w,String state,Instant now) {
        long id=number(w,"id");
        one(store.jdbc().update("UPDATE waitlist_entry SET status=?,finished_at=? WHERE id=? AND status='WAITING'",state,sqlTime(now),id));
        one(store.jdbc().update("UPDATE user_session_quota SET owner_type='NONE',owner_id=NULL,updated_at=? WHERE user_id=? AND session_id=? AND owner_type='WAITLIST' AND owner_id=?",sqlTime(now),number(w,"user_id"),number(w,"session_id"),id));
    }
    private void ensureQuota(long user,long session) {
        store.jdbc().update("INSERT INTO user_session_quota(user_id,session_id,updated_at) VALUES(?,?,UTC_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE user_id=user_id",user,session);
    }
    private Map<String,Object> head(long tier) { return store.optional("SELECT * FROM waitlist_entry WHERE tier_id=? AND status='WAITING' ORDER BY id LIMIT 1",tier); }
    private static void checkOwner(Map<String,Object> q,Map<String,Object> w) {
        if(!"WAITLIST".equals(q.get("owner_type")) || number(q,"owner_id")!=number(w,"id") || !"WAITING".equals(w.get("status"))) { throw new IllegalStateException("WAITLIST_OWNER_INCONSISTENT"); }
    }
    private static boolean open(Map<String,Object> s,Instant now) {
        return "OPEN".equals(s.get("state")) && !now.isBefore(instant(s.get("booking_open_at"))) && now.isBefore(instant(s.get("booking_close_at")));
    }
    private Map<String,Object> view(Map<String,Object> row) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(var field:Map.of("waitlistId","id","sessionId","session_id","tierId","tier_id").entrySet()) { result.put(field.getKey(),Long.toString(number(row,field.getValue()))); }
        result.put("status",row.get("status"));result.put("orderId",row.get("order_id")==null?null:Long.toString(number(row,"order_id")));
        result.put("createdAt",time(row.get("created_at")));result.put("serverTime",time(runtime.afterLocks(store.jdbc())));
        return result;
    }
    private static byte[] hash(long session,long tier) {
        try { return MessageDigest.getInstance("SHA-256").digest(("waitlist|"+session+"|"+tier+"|1").getBytes(StandardCharsets.UTF_8)); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
