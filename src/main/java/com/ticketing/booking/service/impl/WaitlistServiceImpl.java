package com.ticketing.booking.service.impl;

import com.ticketing.booking.config.RuntimeServices;
import com.ticketing.booking.domain.ApiFailure;
import com.ticketing.booking.model.dto.JoinWaitlistDTO;
import com.ticketing.booking.model.po.BookingRows.*;
import com.ticketing.booking.model.vo.PageVO;
import com.ticketing.booking.model.vo.WaitlistVO;
import com.ticketing.booking.repository.DatabaseTimeRepository;
import com.ticketing.booking.repository.WaitlistRepository;
import com.ticketing.booking.service.EntryLimiter;
import com.ticketing.booking.service.WaitlistService;
import static com.ticketing.booking.domain.Values.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** One ticket and one tier, committed FIFO; transaction ownership stays in the service. */
@Service
@Profile("local-sim")
public class WaitlistServiceImpl implements WaitlistService {
    private final WaitlistRepository repository;
    private final DatabaseTimeRepository clock;
    private final RuntimeServices runtime;
    private final EntryLimiter limiter;
    private final TransactionTemplate tx;
    public WaitlistServiceImpl(WaitlistRepository repository,DatabaseTimeRepository clock,RuntimeServices runtime,EntryLimiter limiter,PlatformTransactionManager manager) {
        this.repository=repository;this.clock=clock;this.runtime=runtime;this.limiter=limiter;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(2);tx.setTimeout(8);
    }
    @Override public WaitlistVO join(JoinWaitlistDTO command) {
        long user=command.userId(),session=command.sessionId(),tier=command.tierId();String key=command.idempotencyKey();
        if(key==null || !key.matches("[A-Za-z0-9._:-]{8,64}")) { throw new ApiFailure(400,"INVALID_ARGUMENT"); }
        byte[] hash=hash(session,tier);
        var existing=prior(user,key,hash);if(existing!=null) { return existing; }
        limiter.check(user,tier,runtime.afterLocks(clock));
        return tx.execute(status->{
            var s=repository.lockSession(session);var t=repository.lockTier(tier,session);
            if(s==null || t==null) { throw new ApiFailure(404,"RESOURCE_NOT_FOUND"); }
            repository.ensureQuota(user,session);var q=repository.lockQuota(user,session);
            var repeat=prior(user,key,hash);if(repeat!=null) { return repeat; }
            Instant now=runtime.afterLocks(clock);
            if(!open(s,now)) { throw new ApiFailure(409,"SESSION_NOT_OPEN"); }
            if(!"NONE".equals(q.ownerType())) { throw new ApiFailure(409,"QUOTA_ALREADY_HELD"); }
            if(t.available()>0 && repository.firstWaiting(tier)==null) { throw new ApiFailure(409,"TICKETS_AVAILABLE"); }
            long id=repository.insertEntry(user,session,tier,key,hash,now);one(repository.claimQuota(id,user,session,now));return query(user,id);
        });
    }
    private WaitlistVO prior(long user,String key,byte[] hash) {
        var row=repository.findIdempotency(user,key);if(row==null) { return null; }
        if(!MessageDigest.isEqual(hash,row.payloadHash())) { throw new ApiFailure(409,"IDEMPOTENCY_CONFLICT"); }return view(row);
    }
    @Override public WaitlistVO query(long user,long id) {
        var row=repository.findOwned(id,user);if(row==null) { throw new ApiFailure(404,"RESOURCE_NOT_FOUND"); }return view(row);
    }
    @Override public PageVO<WaitlistVO> list(long user,int page,int size) {
        if(page<1 || page>10000 || size<1 || size>50) { throw new ApiFailure(400,"INVALID_ARGUMENT"); }
        int offset=(page-1)*size;var rows=repository.listOwned(user,size+1,offset);
        return new PageVO<>(rows.stream().limit(size).map(this::view).toList(),page,size,rows.size()>size);
    }
    @Override public WaitlistVO cancel(long user,long id) {
        query(user,id);
        return tx.execute(status->{
            var route=repository.route(id);long session=route.sessionId(),tier=route.tierId();
            var s=requiredSession(session);repository.lockTier(tier);var q=repository.lockQuota(user,session);var w=repository.lockEntry(id);
            if("ALLOCATED".equals(w.status())) { throw new ApiFailure(409,"WAITLIST_ALREADY_ALLOCATED"); }
            if(!"WAITING".equals(w.status())) { return view(w); }
            checkOwner(q,w);Instant now=runtime.afterLocks(clock);
            String end=now.isBefore(s.closesAt())?"CANCELLED":"EXPIRED";release(w,end,now);return query(user,id);
        });
    }
    @Override public void advance(long tier) {
        tx.executeWithoutResult(status->{
            long session=repository.tierSession(tier);var s=requiredSession(session);var t=repository.lockTier(tier);
            var head=repository.firstWaiting(tier);if(head==null) { return; }
            long id=head.id(),user=head.userId();var q=repository.lockQuota(user,session);var w=repository.lockEntry(id);
            checkOwner(q,w);Instant now=runtime.afterLocks(clock);
            if(!now.isBefore(s.closesAt())) { release(w,"EXPIRED",now);return; }
            if(!open(s,now) || t.available()==0) { return; }
            one(repository.reserveTicket(tier));int price=t.unitPriceCent();Instant deadline=earlier(now.plusSeconds(600),s.closesAt());
            long order=repository.insertOrder(id,user,session,tier,price,now,deadline);
            one(repository.allocateQuota(order,user,session,id,now));one(repository.allocateEntry(id,order,now));
            repository.insertReservation(tier,order,t.available()-1,now);runtime.at("WAITLIST_BEFORE_COMMIT",id);
        });
    }
    private Session requiredSession(long id) { var s=repository.lockSession(id);if(s==null) { throw new IllegalStateException("MISSING_FACT"); }return s; }
    private void release(Waitlist w,String state,Instant now) {
        one(repository.finishWaiting(w.id(),state,now));one(repository.releaseQuota(w.userId(),w.sessionId(),w.id(),now));
    }
    private static void checkOwner(Quota q,Waitlist w) {
        if(!"WAITLIST".equals(q.ownerType()) || q.ownerId()==null || q.ownerId()!=w.id() || !"WAITING".equals(w.status())) { throw new IllegalStateException("WAITLIST_OWNER_INCONSISTENT"); }
    }
    private static boolean open(Session s,Instant now) { return "OPEN".equals(s.state()) && !now.isBefore(s.opensAt()) && now.isBefore(s.closesAt()); }
    private WaitlistVO view(Waitlist row) {
        return new WaitlistVO(Long.toString(row.id()),Long.toString(row.sessionId()),Long.toString(row.tierId()),row.status(),row.orderId()==null?null:Long.toString(row.orderId()),time(row.createdAt()),time(runtime.afterLocks(clock)));
    }
    private static byte[] hash(long session,long tier) {
        try { return MessageDigest.getInstance("SHA-256").digest(("waitlist|"+session+"|"+tier+"|1").getBytes(StandardCharsets.UTF_8)); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
