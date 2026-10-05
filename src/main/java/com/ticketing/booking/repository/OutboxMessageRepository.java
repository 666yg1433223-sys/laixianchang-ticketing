package com.ticketing.booking.repository;

import com.ticketing.booking.model.po.OutboxMessagePO;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Outbox transport persistence; business changes are owned by the transaction services. */
@Repository
public class OutboxMessageRepository {
    static final RowMapper<OutboxMessagePO> ROW = (rs, n) -> new OutboxMessagePO(rs.getLong("id"),
        rs.getString("event_type"), rs.getLong("aggregate_id"), rs.getObject("booking_request_id", Long.class),
        rs.getObject("payment_attempt_id", Long.class), rs.getString("payload"), rs.getString("state"),
        rs.getInt("attempts"), rs.getString("last_error_code"), rs.getLong("recovery_generation"));
    private final JdbcTemplate jdbc;

    public OutboxMessageRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Long> readyBookingMessages() {
        return jdbc.queryForList("SELECT id FROM outbox_event WHERE event_type='BOOKING_REQUESTED' AND state IN ('READY','PUBLISHED') AND next_attempt_at<=UTC_TIMESTAMP(3) AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(3)) AND (published_at IS NULL OR published_at<UTC_TIMESTAMP(3)-INTERVAL 30 SECOND) ORDER BY id LIMIT 20", Long.class);
    }

    public boolean claimBookingMessage(long id, String lease) {
        return jdbc.update("UPDATE outbox_event SET lease_token=?,lease_until=UTC_TIMESTAMP(3)+INTERVAL 15 SECOND WHERE id=? AND event_type='BOOKING_REQUESTED' AND state IN ('READY','PUBLISHED') AND next_attempt_at<=UTC_TIMESTAMP(3) AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(3)) AND (published_at IS NULL OR published_at<UTC_TIMESTAMP(3)-INTERVAL 30 SECOND)", lease, id) == 1;
    }

    public OutboxMessagePO find(long id) {
        var rows = jdbc.query("SELECT * FROM outbox_event WHERE id=?", ROW, id);
        if (rows.size() > 1) { throw new IllegalStateException("MULTIPLE_ROWS"); }
        return rows.isEmpty() ? null : rows.get(0);
    }

    public OutboxMessagePO require(long id) {
        var row = find(id);
        if (row == null) { throw new IllegalStateException("MISSING_FACT"); }
        return row;
    }

    public void published(long id, String lease) {
        jdbc.update("UPDATE outbox_event SET state='PUBLISHED',published_at=UTC_TIMESTAMP(3),lease_token=NULL,lease_until=NULL WHERE id=? AND lease_token=? AND state IN ('READY','PUBLISHED')", id, lease);
    }

    public void publicationFailed(long id, String lease) {
        jdbc.update("UPDATE outbox_event SET lease_token=NULL,lease_until=NULL,last_error_code='STREAM_PUBLISH_FAILED',next_attempt_at=UTC_TIMESTAMP(3)+INTERVAL 5 SECOND WHERE id=? AND lease_token=? AND state IN ('READY','PUBLISHED')", id, lease);
    }

    public boolean terminalBookingMessage(long requestId) {
        var rows = jdbc.query("SELECT r.status,e.state FROM booking_request r JOIN outbox_event e ON e.booking_request_id=r.id AND e.event_type='BOOKING_REQUESTED' WHERE r.id=?",
            (rs, n) -> !"ACCEPTED".equals(rs.getString("status")) && java.util.Set.of("DONE", "DEAD").contains(rs.getString("state")), requestId);
        if (rows.size() != 1) { throw new IllegalStateException(rows.isEmpty() ? "MISSING_FACT" : "MULTIPLE_ROWS"); }
        return rows.get(0);
    }

    public void recordRejectedMessage(String id, String reason) {
        jdbc.update("INSERT INTO invalid_stream_message(message_id,reason,summary) VALUES(?,?,?) ON DUPLICATE KEY UPDATE message_id=message_id", id, reason, "Rejected booking stream message; payload not retained");
    }
}
