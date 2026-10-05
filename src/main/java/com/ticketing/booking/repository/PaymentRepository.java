package com.ticketing.booking.repository;

import com.ticketing.booking.model.po.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import static com.ticketing.booking.domain.Values.*;

/** Payment persistence; transaction boundaries and lock ordering belong to the service. */
@Repository
public class PaymentRepository {
    private final JdbcTemplate jdbc;
    public PaymentRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private <T> T optional(String sql, RowMapper<T> mapper, Object... args) {
        var rows=jdbc.query(sql,mapper,args);
        if(rows.size()>1) { throw new IllegalStateException("MULTIPLE_ROWS"); }
        return rows.isEmpty()?null:rows.get(0);
    }
    private static <T> T required(T value) {
        if(value==null) { throw new IllegalStateException("MISSING_FACT"); }
        return value;
    }
    static Instant instant(ResultSet rs,String column) throws SQLException {
        Object value=rs.getObject(column);return value==null?null:com.ticketing.booking.domain.Values.instant(value);
    }
    static Long nullableLong(ResultSet rs,String column) throws SQLException {
        var value=(Number)rs.getObject(column);return value==null?null:value.longValue();
    }
    static Integer nullableInt(ResultSet rs,String column) throws SQLException {
        var value=(Number)rs.getObject(column);return value==null?null:value.intValue();
    }
    public PaymentAttemptPO findByKey(long user,String key) {
        return optional("SELECT * FROM payment_attempt WHERE user_id=? AND idempotency_key=?",PaymentRepository::attempt,user,key);
    }
    public PaymentAttemptPO lockByKey(long user,String key) {
        return optional("SELECT * FROM payment_attempt WHERE user_id=? AND idempotency_key=? FOR UPDATE",PaymentRepository::attempt,user,key);
    }
    public PaymentAttemptPO requireAttempt(long id) {
        return required(optional("SELECT * FROM payment_attempt WHERE id=?",PaymentRepository::attempt,id));
    }
    public PaymentAttemptPO lockAttempt(long id) {
        return required(optional("SELECT * FROM payment_attempt WHERE id=? FOR UPDATE",PaymentRepository::attempt,id));
    }
    public boolean attemptExists(long id) {
        return optional("SELECT id FROM payment_attempt WHERE id=?",(rs,row)->rs.getLong("id"),id)!=null;
    }
    public long orderId(long id) {
        return required(optional("SELECT order_id FROM payment_attempt WHERE id=?",(rs,row)->rs.getLong("order_id"),id));
    }
    public long createAttempt(long order,long user,long session,String key,long sequence,long amount,String currency,
            Instant created,String planned,Instant plannedAt,Instant deadline) {
        return PaymentJdbcSupport.insert(jdbc,"INSERT INTO payment_attempt(order_id,user_id,session_id,idempotency_key,attempt_no,amount_cent,currency,created_at,planned_status,planned_at,resolution_deadline) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            order,user,session,key,sequence,amount,currency,sqlTime(created),planned,sqlTime(plannedAt),sqlTime(deadline));
    }
    public void attachCurrentAttempt(long attempt,long order,long user) {
        one(jdbc.update("UPDATE booking_order SET current_attempt_id=? WHERE id=? AND user_id=? AND status='HOLD'",attempt,order,user));
    }
    public PaymentQueryPO findForUser(long id,long user,boolean product) {
        String refunds=product?",o.current_refund_id,o.refunded_at,s.start_at AS refund_deadline":"";
        return optional("SELECT a.*,o.status AS order_status,o.current_attempt_id,o.paid_attempt_id,o.paid_at"+refunds+" FROM payment_attempt a JOIN booking_order o ON o.id=a.order_id AND o.user_id=a.user_id AND o.session_id=a.session_id JOIN event_session s ON s.id=o.session_id WHERE a.id=? AND a.user_id=?",
            (rs,row)->new PaymentQueryPO(attempt(rs,row),rs.getString("order_status"),nullableLong(rs,"current_attempt_id"),nullableLong(rs,"paid_attempt_id"),instant(rs,"paid_at"),product,
                product?nullableLong(rs,"current_refund_id"):null,product?instant(rs,"refunded_at"):null,product?instant(rs,"refund_deadline"):null),id,user);
    }
    public void recordResult(PaymentAttemptPO a,byte[] digest) {
        one(jdbc.update("UPDATE payment_attempt SET result_status=?,result_id=?,result_at=?,result_reason=?,result_digest=? WHERE id=? AND result_status='PENDING'",
            a.resultStatus(),a.resultId(),sqlTime(a.resultAt()),a.resultReason(),digest,a.id()));
    }
    public void createResultEvent(long id,String resultId,Instant now) {
        PaymentJdbcSupport.insert(jdbc,"INSERT INTO outbox_event(event_type,aggregate_id,payment_attempt_id,payload,next_attempt_at,created_at) VALUES('PAYMENT_RESULT_RECORDED',?,?,JSON_OBJECT('schemaVersion',1,'attemptId',?,'resultId',?),?,?)",
            id,id,Long.toString(id),resultId,sqlTime(now),sqlTime(now));
    }
    public PaymentResultEventPO observeReadyEvent(long id) {
        return optional("SELECT recovery_generation,attempts,next_attempt_at FROM outbox_event WHERE payment_attempt_id=? AND event_type='PAYMENT_RESULT_RECORDED' AND state='READY'",
            (rs,row)->new PaymentResultEventPO(0,0,id,"READY",rs.getLong("recovery_generation"),rs.getInt("attempts"),instant(rs,"next_attempt_at"),null),id);
    }
    public PaymentResultEventPO lockResultEvent(long id) {
        return optional("SELECT * FROM outbox_event WHERE event_type='PAYMENT_RESULT_RECORDED' AND aggregate_id=? FOR UPDATE",PaymentRepository::event,id);
    }
    public PaymentResultEventPO requireLockedResultEvent(long id) { return required(lockResultEvent(id)); }
    public PaymentResultEventPO lockResultEventByAttempt(long id) {
        return optional("SELECT * FROM outbox_event WHERE payment_attempt_id=? AND event_type='PAYMENT_RESULT_RECORDED' FOR UPDATE",PaymentRepository::event,id);
    }
    public void markOrderPaid(long id,Instant now,long order) {
        one(jdbc.update("UPDATE booking_order SET status='PAID',paid_attempt_id=?,paid_at=? WHERE id=? AND status='HOLD' AND current_attempt_id=?",id,sqlTime(now),order,id));
    }
    public void reverse(long id,Instant now,String compensationId) {
        one(jdbc.update("UPDATE payment_attempt SET settlement_status='REVERSED',settled_at=?,compensation_id=?,compensation_cent=amount_cent,compensation_at=? WHERE id=? AND settlement_status='UNRESOLVED'",sqlTime(now),compensationId,sqlTime(now),id));
    }
    public void settle(long id,String state,Instant now) {
        one(jdbc.update("UPDATE payment_attempt SET settlement_status=?,settled_at=? WHERE id=? AND settlement_status='UNRESOLVED'",state,sqlTime(now),id));
    }
    public void markForReview(long id,String reason) {
        one(jdbc.update("UPDATE payment_attempt SET settlement_status='REVIEW',review_reason=? WHERE id=? AND settlement_status='UNRESOLVED'",reason,id));
    }
    public void finishEvent(long event,Instant now) {
        one(jdbc.update("UPDATE outbox_event SET state='DONE',next_attempt_at=NULL,finished_at=?,last_error_code=NULL WHERE id=? AND state='READY'",sqlTime(now),event));
    }
    public void deadEvent(long event,Instant now,String reason) {
        one(jdbc.update("UPDATE outbox_event SET state='DEAD',next_attempt_at=NULL,finished_at=?,last_error_code=? WHERE id=? AND state='READY'",sqlTime(now),reason,event));
    }
    public void countFailure(long event,int count) {
        one(jdbc.update("UPDATE outbox_event SET attempts=?,last_error_code='PAYMENT_TECHNICAL_FAILURE' WHERE id=?",count,event));
    }
    public void scheduleRetry(long event,Instant due) {
        one(jdbc.update("UPDATE outbox_event SET next_attempt_at=? WHERE id=? AND state='READY'",sqlTime(due),event));
    }
    public void lockOperations(long id,long round) {
        jdbc.queryForList("SELECT operation_id FROM sim_payment_operation WHERE attempt_id=? AND generation=? AND status='ACCEPTED' ORDER BY operation_id FOR UPDATE",id,round);
    }
    public void completeOperations(long id,long round,String result,Instant now) {
        jdbc.update("UPDATE sim_payment_operation SET status='COMPLETED',result=?,completed_at=? WHERE attempt_id=? AND generation=? AND status='ACCEPTED'",result,sqlTime(now),id,round);
    }
    public List<PaymentLedgerPO> reservationLedger(long order) {
        return jdbc.query("SELECT action,delta,tier_id FROM inventory_ledger WHERE order_id=?",(rs,row)->new PaymentLedgerPO(rs.getString("action"),rs.getInt("delta"),rs.getLong("tier_id")),order);
    }
    public void reopenEvent(long event,long oldRound,long newRound,Instant now) {
        one(jdbc.update("UPDATE outbox_event SET recovery_generation=?,state='READY',attempts=0,next_attempt_at=?,finished_at=NULL,last_error_code=NULL WHERE id=? AND state='DEAD' AND recovery_generation=?",newRound,sqlTime(now),event,oldRound));
    }
    public void reopenSettlement(long id) {
        one(jdbc.update("UPDATE payment_attempt SET settlement_status='UNRESOLVED',review_reason=NULL WHERE id=? AND settlement_status='REVIEW'",id));
    }
    static PaymentAttemptPO attempt(ResultSet rs, int row) throws SQLException {
        return new PaymentAttemptPO(rs.getLong("id"),
            rs.getLong("order_id"),
            rs.getLong("user_id"),
            rs.getLong("session_id"),
            rs.getString("idempotency_key"),
            rs.getInt("attempt_no"),
            rs.getInt("amount_cent"),
            rs.getString("currency"),
            instant(rs,"created_at"),
            rs.getString("planned_status"),
            instant(rs,"planned_at"),
            instant(rs,"resolution_deadline"),
            rs.getString("result_status"),
            rs.getString("result_id"),
            instant(rs,"result_at"),
            rs.getString("result_reason"),
            rs.getBytes("result_digest"),
            rs.getString("settlement_status"),
            instant(rs,"settled_at"),
            rs.getString("review_reason"),
            rs.getString("compensation_id"),
            nullableInt(rs,"compensation_cent"),
            instant(rs,"compensation_at"));
    }
    static PaymentResultEventPO event(ResultSet rs,int row) throws SQLException {
        return new PaymentResultEventPO(rs.getLong("id"),rs.getLong("aggregate_id"),nullableLong(rs,"payment_attempt_id"),rs.getString("state"),
            rs.getLong("recovery_generation"),rs.getInt("attempts"),instant(rs,"next_attempt_at"),rs.getString("last_error_code"));
    }
}
