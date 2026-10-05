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
public class RefundRepository {
    private final JdbcTemplate jdbc;
    public RefundRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
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
    public RefundRequestPO findByKey(long user,String key) {
        return optional("SELECT * FROM refund_request WHERE user_id=? AND idempotency_key=?",RefundRepository::refund,user,key);
    }
    public RefundRequestPO lockByKey(long user,String key) {
        return optional("SELECT * FROM refund_request WHERE user_id=? AND idempotency_key=? FOR UPDATE",RefundRepository::refund,user,key);
    }
    public Long lockRefundIdByOrder(long order) {
        return optional("SELECT id FROM refund_request WHERE order_id=? FOR UPDATE",(rs,row)->rs.getLong("id"),order);
    }
    public boolean refundExists(long id) {
        return optional("SELECT id FROM refund_request WHERE id=?",(rs,row)->rs.getLong("id"),id)!=null;
    }
    public RefundRequestPO requireRefund(long id) {
        return required(optional("SELECT * FROM refund_request WHERE id=?",RefundRepository::refund,id));
    }
    public RefundRequestPO lockRefund(long id) {
        return required(optional("SELECT * FROM refund_request WHERE id=? FOR UPDATE",RefundRepository::refund,id));
    }
    public long orderId(long id) {
        return required(optional("SELECT order_id FROM refund_request WHERE id=?",(rs,row)->rs.getLong("order_id"),id));
    }
    public Instant sessionStart(long session) {
        return required(optional("SELECT start_at FROM event_session WHERE id=?",(rs,row)->instant(rs,"start_at"),session));
    }
    public long createRefund(long order,long user,long session,long payment,String key,long amount,String currency,String reason,Instant now) {
        return PaymentJdbcSupport.insert(jdbc,"INSERT INTO refund_request(order_id,user_id,session_id,payment_attempt_id,idempotency_key,amount_cent,currency,reason,requested_at,next_attempt_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
            order,user,session,payment,key,amount,currency,reason,sqlTime(now),sqlTime(now));
    }
    public void markOrderRefunding(long refund,long order,long user) {
        one(jdbc.update("UPDATE booking_order SET status='REFUNDING',current_refund_id=? WHERE id=? AND user_id=? AND status='PAID' AND current_refund_id IS NULL",refund,order,user));
    }
    public RefundQueryPO findForUser(long user,long id) {
        return optional("SELECT r.*,o.status AS order_status FROM refund_request r JOIN booking_order o ON o.id=r.order_id AND o.user_id=r.user_id AND o.session_id=r.session_id WHERE r.id=? AND r.user_id=?",RefundRepository::query,id,user);
    }
    public RefundQueryPO findForAdmin(long id) {
        return optional("SELECT r.*,o.status AS order_status FROM refund_request r JOIN booking_order o ON o.id=r.order_id AND o.user_id=r.user_id AND o.session_id=r.session_id WHERE r.id=?",RefundRepository::query,id);
    }
    public List<RefundQueryPO> listForAdmin(String status,int page,int size) {
        return jdbc.query("SELECT r.*,o.status AS order_status FROM refund_request r JOIN booking_order o ON o.id=r.order_id WHERE r.status=? ORDER BY r.id DESC LIMIT ? OFFSET ?",RefundRepository::query,status,size+1,(page-1)*size);
    }
    public RefundGenerationPO observePending(long id) {
        return optional("SELECT recovery_generation,attempts,next_attempt_at FROM refund_request WHERE id=? AND status='PENDING'",
            (rs,row)->new RefundGenerationPO(rs.getLong("recovery_generation"),rs.getInt("attempts"),instant(rs,"next_attempt_at")),id);
    }
    public void succeed(long id,long generation,Instant now) {
        one(jdbc.update("UPDATE refund_request SET status='SUCCEEDED',completed_at=?,next_attempt_at=NULL,review_reason=NULL,last_error_code=NULL WHERE id=? AND status='PENDING' AND recovery_generation=?",sqlTime(now),id,generation));
    }
    public void markOrderRefunded(long order,long id,Instant now) {
        one(jdbc.update("UPDATE booking_order SET status='REFUNDED',closed_at=?,close_reason='USER_REFUNDED',refunded_at=? WHERE id=? AND status='REFUNDING' AND current_refund_id=?",sqlTime(now),sqlTime(now),order,id));
    }
    public void releaseStock(long tier) {
        one(jdbc.update("UPDATE ticket_tier SET available=available+1 WHERE id=? AND available<capacity",tier));
    }
    public void releaseQuota(long user,long session,long order,Instant now) {
        one(jdbc.update("UPDATE user_session_quota SET owner_type='NONE',owner_id=NULL,updated_at=? WHERE user_id=? AND session_id=? AND owner_type='ORDER' AND owner_id=?",sqlTime(now),user,session,order));
    }
    public void recordRelease(long tier,long order,long available,Instant now) {
        PaymentJdbcSupport.insert(jdbc,"INSERT INTO inventory_ledger(tier_id,order_id,action,delta,available_after,created_at) VALUES(?,?,'RELEASE',1,?,?)",tier,order,available,sqlTime(now));
    }
    public List<Long> dueRefunds() {
        return jdbc.queryForList("SELECT id FROM refund_request WHERE status='PENDING' AND next_attempt_at<=UTC_TIMESTAMP(3) ORDER BY next_attempt_at,id LIMIT 20",Long.class);
    }
    public void exhaustRetries(long id,long generation,int count) {
        one(jdbc.update("UPDATE refund_request SET status='REVIEW',attempts=?,next_attempt_at=NULL,review_reason='RETRY_EXHAUSTED',last_error_code='REFUND_TECHNICAL_FAILURE' WHERE id=? AND status='PENDING' AND recovery_generation=?",count,id,generation));
    }
    public void scheduleRetry(long id,long generation,int count,Instant due) {
        one(jdbc.update("UPDATE refund_request SET attempts=?,next_attempt_at=?,last_error_code='REFUND_TECHNICAL_FAILURE' WHERE id=? AND status='PENDING' AND recovery_generation=?",count,sqlTime(due),id,generation));
    }
    public RefundRetryPO lockRetryByKey(long operator,String key) {
        return optional("SELECT * FROM refund_retry WHERE operator_id=? AND idempotency_key=? FOR UPDATE",RefundRepository::retry,operator,key);
    }
    public RefundRetryPO findRetryByKey(long operator,String key) {
        return optional("SELECT * FROM refund_retry WHERE operator_id=? AND idempotency_key=?",RefundRepository::retry,operator,key);
    }
    public void reopen(long id,long previousGeneration,long nextGeneration,Instant now) {
        one(jdbc.update("UPDATE refund_request SET status='PENDING',attempts=0,recovery_generation=?,next_attempt_at=?,review_reason=NULL,last_error_code=NULL WHERE id=? AND status='REVIEW' AND recovery_generation=?",nextGeneration,sqlTime(now),id,previousGeneration));
    }
    public void recordRecovery(long id,long operator,String key,String reason,long generation,Instant now) {
        PaymentJdbcSupport.insert(jdbc,"INSERT INTO refund_retry(refund_id,operator_id,idempotency_key,reason,generation,created_at) VALUES(?,?,?,?,?,?)",id,operator,key,reason,generation,sqlTime(now));
    }
    private static RefundQueryPO query(ResultSet rs,int row) throws SQLException {
        return new RefundQueryPO(refund(rs,row),rs.getString("order_status"));
    }
    static RefundRequestPO refund(ResultSet rs, int row) throws SQLException {
        return new RefundRequestPO(rs.getLong("id"),
            rs.getLong("order_id"),
            rs.getLong("user_id"),
            rs.getLong("session_id"),
            rs.getLong("payment_attempt_id"),
            rs.getString("idempotency_key"),
            rs.getInt("amount_cent"),
            rs.getString("currency"),
            rs.getString("reason"),
            rs.getString("status"),
            instant(rs,"requested_at"),
            instant(rs,"completed_at"),
            instant(rs,"next_attempt_at"),
            rs.getInt("attempts"),
            rs.getLong("recovery_generation"),
            rs.getString("review_reason"),
            rs.getString("last_error_code"));
    }
    static RefundRetryPO retry(ResultSet rs, int row) throws SQLException {
        return new RefundRetryPO(rs.getLong("id"),
            rs.getLong("refund_id"),
            rs.getLong("operator_id"),
            rs.getString("idempotency_key"),
            rs.getString("reason"),
            rs.getLong("generation"),
            instant(rs,"created_at"));
    }
}
