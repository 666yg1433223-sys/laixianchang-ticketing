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
public class PaymentManagementRepository {
    private final JdbcTemplate jdbc;
    public PaymentManagementRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
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
    private static String slots(Set<Long> scope) { return String.join(",",Collections.nCopies(scope.size(),"?")); }
    private static ArrayList<Object> args(Set<Long> scope) { return new ArrayList<>(scope); }
    public List<PaymentReviewPO> reviewPage(Set<Long> scope,long after,int limit) {
        var args=args(scope);args.add(after);args.add(limit);
        return jdbc.query("SELECT a.id,a.order_id,a.result_id,a.result_status,a.settlement_status,a.review_reason,e.attempts,e.last_error_code,e.recovery_generation FROM payment_attempt a JOIN outbox_event e ON e.payment_attempt_id=a.id AND e.event_type='PAYMENT_RESULT_RECORDED' WHERE a.id IN ("+slots(scope)+") AND a.id>? AND a.settlement_status='REVIEW' AND e.state='DEAD' ORDER BY a.id LIMIT ?",PaymentManagementRepository::review,args.toArray());
    }
    public Long findAttemptByResult(Set<Long> scope,String result) {
        var args=args(scope);args.add(result);
        return optional("SELECT id FROM payment_attempt WHERE id IN ("+slots(scope)+") AND result_id=?",(rs,row)->rs.getLong("id"),args.toArray());
    }
    public PaymentOperationPO findOperation(Set<Long> scope,String operation) {
        var args=args(scope);args.add(operation);
        return optional("SELECT * FROM sim_payment_operation WHERE attempt_id IN ("+slots(scope)+") AND operation_id=?",PaymentManagementRepository::operation,args.toArray());
    }
    public PaymentOperationPO findByKey(Set<Long> scope,long actor,String key) {
        var args=args(scope);args.add(actor);args.add(key);
        return optional("SELECT * FROM sim_payment_operation WHERE attempt_id IN ("+slots(scope)+") AND operator_id=? AND idempotency_key=?",PaymentManagementRepository::operation,args.toArray());
    }
    public PaymentOperationPO requireOperation(String operation) {
        return required(optional("SELECT * FROM sim_payment_operation WHERE operation_id=?",PaymentManagementRepository::operation,operation));
    }
    public void recordOperation(PaymentOperationPO op) {
        one(jdbc.update("INSERT INTO sim_payment_operation(operation_id,operator_id,action,attempt_id,target_id,idempotency_key,reason,deliveries,parameter_digest,before_settlement,before_event,before_attempts,before_error,generation,accepted_at,completed_at,status,result,rejection_reason) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            op.operationId(),op.operatorId(),op.action(),op.attemptId(),op.targetId(),op.idempotencyKey(),op.reason(),op.deliveries(),op.parameterDigest(),op.beforeSettlement(),op.beforeEvent(),op.beforeAttempts(),op.beforeError(),op.generation(),
            op.acceptedAt()==null?null:sqlTime(op.acceptedAt()),op.completedAt()==null?null:sqlTime(op.completedAt()),op.status(),op.result(),op.rejectionReason()));
    }
    static PaymentOperationPO operation(ResultSet rs, int row) throws SQLException {
        return new PaymentOperationPO(rs.getString("operation_id"),
            rs.getLong("operator_id"),
            rs.getString("action"),
            rs.getLong("attempt_id"),
            rs.getString("target_id"),
            rs.getString("idempotency_key"),
            rs.getString("reason"),
            rs.getInt("deliveries"),
            rs.getBytes("parameter_digest"),
            rs.getString("before_settlement"),
            rs.getString("before_event"),
            rs.getInt("before_attempts"),
            rs.getString("before_error"),
            rs.getLong("generation"),
            instant(rs,"accepted_at"),
            instant(rs,"completed_at"),
            rs.getString("status"),
            rs.getString("result"),
            rs.getString("rejection_reason"));
    }
    static PaymentReviewPO review(ResultSet rs, int row) throws SQLException {
        return new PaymentReviewPO(rs.getLong("id"),
            rs.getLong("order_id"),
            rs.getString("result_id"),
            rs.getString("result_status"),
            rs.getString("settlement_status"),
            rs.getString("review_reason"),
            rs.getInt("attempts"),
            rs.getString("last_error_code"),
            rs.getLong("recovery_generation"));
    }
}
