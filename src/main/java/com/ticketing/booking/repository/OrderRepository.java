package com.ticketing.booking.repository;

import com.ticketing.booking.model.po.BookingRows.*;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import static com.ticketing.booking.repository.BookingJdbcSupport.*;
import static com.ticketing.booking.domain.Values.*;

@Repository
public class OrderRepository {
    private final JdbcTemplate jdbc;
    public OrderRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Order findOwned(long id,long user) { return optional(jdbc,"SELECT o.*,s.start_at AS refund_deadline,UTC_TIMESTAMP(3) AS refund_clock FROM booking_order o JOIN event_session s ON s.id=o.session_id WHERE o.id=? AND o.user_id=?",ORDER,id,user); }
    public Order route(long id) { return required(optional(jdbc,"SELECT * FROM booking_order WHERE id=?",ORDER,id)); }
    public void lockSession(long id) { required(optional(jdbc,"SELECT id FROM event_session WHERE id=? FOR SHARE",(r,n)->r.getLong("id"),id)); }
    public Tier lockTier(long tier,long session) { return required(optional(jdbc,"SELECT * FROM ticket_tier WHERE id=? AND session_id=? FOR UPDATE",TIER,tier,session)); }
    public Quota lockQuota(long user,long session) { return optional(jdbc,"SELECT * FROM user_session_quota WHERE user_id=? AND session_id=? FOR UPDATE",QUOTA,user,session); }
    public Request lockRequest(long id) { return required(optional(jdbc,"SELECT * FROM booking_request WHERE id=? FOR UPDATE",REQUEST,id)); }
    public Waitlist lockWaitlist(long id) { return required(optional(jdbc,"SELECT * FROM waitlist_entry WHERE id=? FOR UPDATE",WAITLIST,id)); }
    public Order lockOrder(long id) { return required(optional(jdbc,"SELECT * FROM booking_order WHERE id=? FOR UPDATE",ORDER,id)); }
    public List<Ledger> ledger(long order) { return jdbc.query("SELECT action,delta,tier_id FROM inventory_ledger WHERE order_id=?",(r,n)->new Ledger(r.getString("action"),r.getLong("delta"),r.getLong("tier_id")),order); }
    public int closeHold(long id,String terminal,String reason,Instant now) { return jdbc.update("UPDATE booking_order SET status=?,closed_at=?,close_reason=? WHERE id=? AND status='HOLD'",terminal,sqlTime(now),reason,id); }
    public int releaseTicket(long tier) { return jdbc.update("UPDATE ticket_tier SET available=available+1 WHERE id=? AND available<capacity",tier); }
    public int releaseQuota(long user,long session,long order,Instant now) { return jdbc.update("UPDATE user_session_quota SET owner_type='NONE',owner_id=NULL,updated_at=? WHERE user_id=? AND session_id=? AND owner_type='ORDER' AND owner_id=?",sqlTime(now),user,session,order); }
    public void insertRelease(long tier,long order,long balance,Instant now) { insert(jdbc,"INSERT INTO inventory_ledger(tier_id,order_id,action,delta,available_after,created_at) VALUES(?,?,'RELEASE',1,?,?)",tier,order,balance,sqlTime(now)); }
    public List<OrderSummary> listOwned(long user,int size,int offset) { return jdbc.query("SELECT o.id,e.id event_id,e.title,s.start_at,t.name tier_name FROM booking_order o JOIN event_session s ON s.id=o.session_id JOIN event e ON e.id=s.event_id JOIN ticket_tier t ON t.id=o.tier_id WHERE o.user_id=? ORDER BY o.hold_created_at DESC,o.id DESC LIMIT ? OFFSET ?",(r,n)->new OrderSummary(r.getLong("id"),r.getLong("event_id"),r.getString("title"),timestamp(r,"start_at"),r.getString("tier_name")),user,size,offset); }
}
