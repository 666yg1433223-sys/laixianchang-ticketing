package com.ticketing.booking.repository;

import com.ticketing.booking.model.po.BookingRows.*;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import static com.ticketing.booking.repository.BookingJdbcSupport.*;
import static com.ticketing.booking.domain.Values.*;

@Repository
public class AllocationRepository {
    private final JdbcTemplate jdbc;
    public AllocationRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Request requestRoute(long id) { return required(optional(jdbc,"SELECT * FROM booking_request WHERE id=?",REQUEST,id)); }
    public Session lockSession(long session) { return required(optional(jdbc,"SELECT * FROM event_session WHERE id=? FOR SHARE",SESSION,session)); }
    public Tier lockTier(long tier,long session) { return required(optional(jdbc,"SELECT * FROM ticket_tier WHERE id=? AND session_id=? FOR UPDATE",TIER,tier,session)); }
    public void ensureQuota(long user,long session) { jdbc.update("INSERT INTO user_session_quota(user_id,session_id,owner_type,owner_id,updated_at) VALUES(?,?,'NONE',NULL,UTC_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE user_id=user_id",user,session); }
    public Quota lockQuota(long user,long session) { return required(optional(jdbc,"SELECT * FROM user_session_quota WHERE user_id=? AND session_id=? FOR UPDATE",QUOTA,user,session)); }
    public Request lockRequest(long id) { return required(optional(jdbc,"SELECT * FROM booking_request WHERE id=? FOR UPDATE",REQUEST,id)); }
    public Order lockRequestOrder(long id) { return optional(jdbc,"SELECT * FROM booking_order WHERE request_id=? FOR UPDATE",ORDER,id); }
    public Outbox lockRequestedEvent(long id) { return required(optional(jdbc,"SELECT * FROM outbox_event WHERE aggregate_id=? AND event_type='BOOKING_REQUESTED' FOR UPDATE",OUTBOX,id)); }
    public long waitingCount(long tier) { return jdbc.queryForObject("SELECT COUNT(*) FROM waitlist_entry WHERE tier_id=? AND status='WAITING'",Long.class,tier); }
    public int reserveTicket(long tier) { return jdbc.update("UPDATE ticket_tier SET available=available-1 WHERE id=? AND available>0",tier); }
    public int available(long tier) { return jdbc.queryForObject("SELECT available FROM ticket_tier WHERE id=?",Integer.class,tier); }
    public long insertOrder(long request,long user,long session,long tier,int price,Instant now,Instant expiry) {
        return insert(jdbc,"INSERT INTO booking_order(request_id,user_id,session_id,tier_id,status,unit_price_cent,quantity,payable_cent,currency,hold_created_at,pay_expires_at) VALUES(?,?,?,?,'HOLD',?,1,?,'CNY',?,?)",request,user,session,tier,price,price,sqlTime(now),sqlTime(expiry));
    }
    public int claimOrderQuota(long order,Instant now,long user,long session) { return jdbc.update("UPDATE user_session_quota SET owner_type='ORDER',owner_id=?,updated_at=? WHERE user_id=? AND session_id=? AND owner_type='NONE' AND owner_id IS NULL",order,sqlTime(now),user,session); }
    public void insertReservation(long tier,long order,int balance,Instant now) { insert(jdbc,"INSERT INTO inventory_ledger(tier_id,order_id,action,delta,available_after,created_at) VALUES(?,?,'RESERVE',-1,?,?)",tier,order,balance,sqlTime(now)); }
    public int finishRequest(long id,String state,String reason,Long order,Instant now) { return jdbc.update("UPDATE booking_request SET status=?,reason_code=?,order_id=?,finished_at=? WHERE id=? AND status='ACCEPTED'",state,reason,order,sqlTime(now),id); }
    public int finishEvent(long id,String state,Instant now) { return jdbc.update("UPDATE outbox_event SET state=?,next_attempt_at=NULL,finished_at=? WHERE id=? AND state IN ('READY','PUBLISHED')",state,sqlTime(now),id); }
    public int recordFailure(long id,int failures,int expectedAttempts) { return jdbc.update("UPDATE outbox_event SET attempts=?,last_error_code='T2_TECHNICAL_FAILURE' WHERE id=? AND attempts=? AND state IN ('READY','PUBLISHED')",failures,id,expectedAttempts); }
    public int scheduleRetry(long id,Instant next) { return jdbc.update("UPDATE outbox_event SET next_attempt_at=? WHERE id=? AND state IN ('READY','PUBLISHED')",sqlTime(next),id); }
}
