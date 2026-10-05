package com.ticketing.booking.repository;

import com.ticketing.booking.model.po.*;
import com.ticketing.booking.model.vo.ProcessingBacklogVO;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Recovery queries and lock primitives. The caller owns the encompassing transaction. */
@Repository
public class ProcessingRecoveryRepository {
    private final JdbcTemplate jdbc;

    public ProcessingRecoveryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<OutboxMessagePO> failedMessages(int limit, int offset) {
        return jdbc.query("SELECT * FROM outbox_event WHERE state='DEAD' ORDER BY id DESC LIMIT ? OFFSET ?", OutboxMessageRepository.ROW, limit, offset);
    }

    public ProcessingBacklogVO backlog() {
        return jdbc.queryForObject("SELECT COUNT(*) AS acceptedRequests,COALESCE(MAX(TIMESTAMPDIFF(SECOND,accepted_at,UTC_TIMESTAMP(3))),0) AS oldestAcceptedSeconds FROM booking_request WHERE status='ACCEPTED'",
            (rs, n) -> new ProcessingBacklogVO(rs.getLong("acceptedRequests"), rs.getLong("oldestAcceptedSeconds")));
    }

    public long invalidMessageCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM invalid_stream_message", Long.class);
    }

    public OutboxMessagePO findMessage(long id) {
        return optional("SELECT * FROM outbox_event WHERE id=?", OutboxMessageRepository.ROW, id);
    }

    public OutboxMessagePO lockMessage(long id) {
        return required("SELECT * FROM outbox_event WHERE id=? FOR UPDATE", OutboxMessageRepository.ROW, id);
    }

    public ProcessingRoutePO route(long request) {
        return required("SELECT * FROM booking_request WHERE id=?", (rs, n) -> new ProcessingRoutePO(rs.getLong("id"),
            rs.getLong("user_id"), rs.getLong("session_id"), rs.getLong("tier_id")), request);
    }

    public void lockSession(long session) {
        required("SELECT id FROM event_session WHERE id=? FOR SHARE", (rs, n) -> rs.getLong("id"), session);
    }

    public void lockTier(long tier) {
        required("SELECT id FROM ticket_tier WHERE id=? FOR UPDATE", (rs, n) -> rs.getLong("id"), tier);
    }

    public void lockQuota(long user, long session) {
        optional("SELECT * FROM user_session_quota WHERE user_id=? AND session_id=? FOR UPDATE", (rs, n) -> rs.getLong("user_id"), user, session);
    }

    public void lockRequest(long request) {
        required("SELECT * FROM booking_request WHERE id=? FOR UPDATE", (rs, n) -> rs.getLong("id"), request);
    }

    public ProcessingRetryPO previousRetry(long operator, String key) {
        return optional("SELECT event_id,reason FROM processing_retry WHERE operator_id=? AND idempotency_key=?",
            (rs, n) -> new ProcessingRetryPO(rs.getLong("event_id"), rs.getString("reason")), operator, key);
    }

    public int reopenRequest(long request) {
        return jdbc.update("UPDATE booking_request SET status='ACCEPTED',reason_code=NULL,finished_at=NULL WHERE id=? AND status='PROCESSING_FAILED' AND process_deadline>UTC_TIMESTAMP(3)", request);
    }

    public int reopenMessage(long id) {
        return jdbc.update("UPDATE outbox_event SET state='READY',attempts=0,last_error_code=NULL,finished_at=NULL,next_attempt_at=UTC_TIMESTAMP(3),recovery_generation=recovery_generation+1,lease_token=NULL,lease_until=NULL,published_at=NULL WHERE id=? AND state='DEAD'", id);
    }

    public int recordRetry(long event, long operator, String reason, String key) {
        return jdbc.update("INSERT INTO processing_retry(event_id,operator_id,reason,idempotency_key) VALUES(?,?,?,?)", event, operator, reason, key);
    }

    public Long operationAttempt(String operationId) {
        return optional("SELECT attempt_id FROM sim_payment_operation WHERE operation_id=?", (rs, n) -> rs.getLong("attempt_id"), operationId);
    }

    private <T> T optional(String sql, RowMapper<T> mapper, Object... args) {
        var rows = jdbc.query(sql, mapper, args);
        if (rows.size() > 1) { throw new IllegalStateException("MULTIPLE_ROWS"); }
        return rows.isEmpty() ? null : rows.get(0);
    }

    private <T> T required(String sql, RowMapper<T> mapper, Object... args) {
        T row = optional(sql, mapper, args);
        if (row == null) { throw new IllegalStateException("MISSING_FACT"); }
        return row;
    }
}
