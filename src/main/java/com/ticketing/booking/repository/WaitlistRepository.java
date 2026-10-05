package com.ticketing.booking.repository;

import com.ticketing.booking.model.po.BookingRows.*;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import static com.ticketing.booking.repository.BookingJdbcSupport.*;
import static com.ticketing.booking.domain.Values.*;

@Repository
public class WaitlistRepository {
    private final JdbcTemplate jdbc;
    public WaitlistRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Session lockSession(long id) { return optional(jdbc,"SELECT * FROM event_session WHERE id=? FOR SHARE",SESSION,id); }
    public Tier lockTier(long tier,long session) { return optional(jdbc,"SELECT * FROM ticket_tier WHERE id=? AND session_id=? FOR UPDATE",TIER,tier,session); }
    public Tier lockTier(long tier) { return required(optional(jdbc,"SELECT * FROM ticket_tier WHERE id=? FOR UPDATE",TIER,tier)); }
    public long tierSession(long tier) { return required(optional(jdbc,"SELECT session_id FROM ticket_tier WHERE id=?",(r,n)->r.getLong("session_id"),tier)); }
    public void ensureQuota(long user,long session) { jdbc.update("INSERT INTO user_session_quota(user_id,session_id,updated_at) VALUES(?,?,UTC_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE user_id=user_id",user,session); }
    public Quota lockQuota(long user,long session) { return required(optional(jdbc,"SELECT * FROM user_session_quota WHERE user_id=? AND session_id=? FOR UPDATE",QUOTA,user,session)); }
    public Waitlist findIdempotency(long user,String key) { return optional(jdbc,"SELECT * FROM waitlist_entry WHERE user_id=? AND idempotency_key=?",WAITLIST,user,key); }
    public Waitlist findOwned(long id,long user) { return optional(jdbc,"SELECT * FROM waitlist_entry WHERE id=? AND user_id=?",WAITLIST,id,user); }
    public Waitlist route(long id) { return required(optional(jdbc,"SELECT * FROM waitlist_entry WHERE id=?",WAITLIST,id)); }
    public Waitlist lockEntry(long id) { return required(optional(jdbc,"SELECT * FROM waitlist_entry WHERE id=? FOR UPDATE",WAITLIST,id)); }
    public Waitlist firstWaiting(long tier) { return optional(jdbc,"SELECT * FROM waitlist_entry WHERE tier_id=? AND status='WAITING' ORDER BY id LIMIT 1",WAITLIST,tier); }
    public List<Waitlist> listOwned(long user,int limit,int offset) { return jdbc.query("SELECT * FROM waitlist_entry WHERE user_id=? ORDER BY id DESC LIMIT ? OFFSET ?",WAITLIST,user,limit,offset); }
    public long insertEntry(long user,long session,long tier,String key,byte[] hash,Instant now) { return insert(jdbc,"INSERT INTO waitlist_entry(user_id,session_id,tier_id,idempotency_key,payload_hash,created_at) VALUES(?,?,?,?,?,?)",user,session,tier,key,hash,sqlTime(now)); }
    public int claimQuota(long id,long user,long session,Instant now) { return jdbc.update("UPDATE user_session_quota SET owner_type='WAITLIST',owner_id=?,updated_at=? WHERE user_id=? AND session_id=? AND owner_type='NONE'",id,sqlTime(now),user,session); }
    public int reserveTicket(long tier) { return jdbc.update("UPDATE ticket_tier SET available=available-1 WHERE id=? AND available>0",tier); }
    public long insertOrder(long id,long user,long session,long tier,int price,Instant now,Instant deadline) { return insert(jdbc,"INSERT INTO booking_order(waitlist_entry_id,user_id,session_id,tier_id,status,unit_price_cent,payable_cent,hold_created_at,pay_expires_at) VALUES(?,?,?,?,'HOLD',?,?,?,?)",id,user,session,tier,price,price,sqlTime(now),sqlTime(deadline)); }
    public int allocateQuota(long order,long user,long session,long entry,Instant now) { return jdbc.update("UPDATE user_session_quota SET owner_type='ORDER',owner_id=?,updated_at=? WHERE user_id=? AND session_id=? AND owner_type='WAITLIST' AND owner_id=?",order,sqlTime(now),user,session,entry); }
    public int allocateEntry(long id,long order,Instant now) { return jdbc.update("UPDATE waitlist_entry SET status='ALLOCATED',order_id=?,finished_at=? WHERE id=? AND status='WAITING'",order,sqlTime(now),id); }
    public void insertReservation(long tier,long order,long balance,Instant now) { insert(jdbc,"INSERT INTO inventory_ledger(tier_id,order_id,action,delta,available_after,created_at) VALUES(?,?,'RESERVE',-1,?,?)",tier,order,balance,sqlTime(now)); }
    public int finishWaiting(long id,String state,Instant now) { return jdbc.update("UPDATE waitlist_entry SET status=?,finished_at=? WHERE id=? AND status='WAITING'",state,sqlTime(now),id); }
    public int releaseQuota(long user,long session,long id,Instant now) { return jdbc.update("UPDATE user_session_quota SET owner_type='NONE',owner_id=NULL,updated_at=? WHERE user_id=? AND session_id=? AND owner_type='WAITLIST' AND owner_id=?",sqlTime(now),user,session,id); }
}
