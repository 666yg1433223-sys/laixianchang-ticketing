package com.ticketing.booking.repository;

import com.ticketing.booking.model.po.BookingRows.*;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import static com.ticketing.booking.repository.BookingJdbcSupport.*;
import static com.ticketing.booking.domain.Values.*;

@Repository
public class BookingRepository {
    private final JdbcTemplate jdbc;
    public BookingRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public long acceptedCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM booking_request WHERE status='ACCEPTED'",Long.class); }
    public Session findCatalogue(long session,long tier) { return optional(jdbc,"SELECT s.* FROM event_session s JOIN ticket_tier t ON t.session_id=s.id WHERE s.id=? AND t.id=?",SESSION,session,tier); }
    public Session lockSession(long id) { return required(optional(jdbc,"SELECT * FROM event_session WHERE id=? FOR SHARE",SESSION,id)); }
    public Tier lockTier(long tier,long session) { return required(optional(jdbc,"SELECT * FROM ticket_tier WHERE id=? AND session_id=? FOR UPDATE",TIER,tier,session)); }
    public Request findIdempotency(long user,String key) { return optional(jdbc,"SELECT * FROM booking_request WHERE user_id=? AND idempotency_key=?",REQUEST,user,key); }
    public long insertRequest(long user,long session,long tier,String key,byte[] hash,Instant acceptedAt,Instant deadline) {
        return insert(jdbc,"INSERT INTO booking_request(user_id,session_id,tier_id,quantity,idempotency_key,payload_hash,status,accepted_at,process_deadline) VALUES(?,?,?,1,?,?,'ACCEPTED',?,?)",user,session,tier,key,hash,sqlTime(acceptedAt),sqlTime(deadline));
    }
    public long insertRequestedEvent(long request,Instant acceptedAt) {
        return insert(jdbc,"INSERT INTO outbox_event(event_type,aggregate_id,booking_request_id,payload,next_attempt_at,created_at) VALUES('BOOKING_REQUESTED',?,?,JSON_OBJECT(),?,?)",request,request,sqlTime(acceptedAt),sqlTime(acceptedAt));
    }
    public int updateEventPayload(long event,String payload) { return jdbc.update("UPDATE outbox_event SET payload=? WHERE id=?",payload,event); }
    public BookingStatus findStatus(long request,long user,boolean product) {
        // A single joined read retains the request/order snapshot used by the original status endpoint.
        String refunds=product?",o.current_refund_id,o.refunded_at,s.start_at AS refund_deadline":"";
        return optional(jdbc,"SELECT r.*, o.id AS actual_order_id,o.status AS order_status,o.unit_price_cent,o.quantity AS order_quantity,o.payable_cent,o.currency,o.hold_created_at,o.pay_expires_at,o.closed_at,o.close_reason,o.current_attempt_id,o.paid_attempt_id,o.paid_at"+refunds+" FROM booking_request r LEFT JOIN booking_order o ON o.request_id=r.id AND o.user_id=r.user_id JOIN event_session s ON s.id=r.session_id WHERE r.id=? AND r.user_id=?",(r,n)->{
            var booking=REQUEST.mapRow(r,n);Long orderId=nullableLong(r,"actual_order_id");
            Order order=orderId==null?null:new Order(orderId,booking.id(),null,booking.userId(),booking.sessionId(),booking.tierId(),r.getString("order_status"),r.getInt("unit_price_cent"),r.getInt("order_quantity"),r.getInt("payable_cent"),r.getString("currency"),timestamp(r,"hold_created_at"),timestamp(r,"pay_expires_at"),timestamp(r,"closed_at"),r.getString("close_reason"),nullableLong(r,"current_attempt_id"),nullableLong(r,"paid_attempt_id"),timestamp(r,"paid_at"),product?nullableLong(r,"current_refund_id"):null,product?timestamp(r,"refunded_at"):null,product?timestamp(r,"refund_deadline"):null,null,false,product);
            return new BookingStatus(booking,order);
        },request,user);
    }
}
