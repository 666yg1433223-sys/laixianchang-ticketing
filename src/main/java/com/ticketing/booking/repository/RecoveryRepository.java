package com.ticketing.booking.repository;

import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Bounded, non-locking candidate queries. Business services own every state transition. */
@Repository
public class RecoveryRepository {
    private final JdbcTemplate jdbc;

    public RecoveryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public long latestBookingRequestId() {
        return jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM booking_request", Long.class);
    }

    public List<Long> bookingCandidates(long cursor, long upper, Timestamp now) {
        return jdbc.queryForList("SELECT r.id FROM booking_request r WHERE r.status='ACCEPTED' AND r.id>? AND r.id<=? AND (r.process_deadline<=? OR EXISTS (SELECT 1 FROM outbox_event e WHERE e.aggregate_id=r.id AND e.event_type='BOOKING_REQUESTED' AND e.state='READY' AND e.next_attempt_at<=?)) ORDER BY r.id LIMIT 20", Long.class, cursor, upper, now, now);
    }

    public long latestOrderId() {
        return jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM booking_order", Long.class);
    }

    public List<Long> expiredOrderCandidates(long cursor, long upper, Timestamp now) {
        return jdbc.queryForList("SELECT id FROM booking_order WHERE status='HOLD' AND pay_expires_at<=? AND id>? AND id<=? ORDER BY id LIMIT 20", Long.class, now, cursor, upper);
    }

    public long latestPaymentAttemptId() {
        return jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM payment_attempt", Long.class);
    }

    public List<Long> paymentGenerationCandidates(long cursor, long upper, Timestamp now) {
        return jdbc.queryForList("SELECT id FROM payment_attempt WHERE id>? AND id<=? AND result_status='PENDING' AND (planned_at<=? OR resolution_deadline<=?) ORDER BY id LIMIT 20", Long.class, cursor, upper, now, now);
    }

    public List<Long> paymentSettlementCandidates(long cursor, long upper, Timestamp now) {
        return jdbc.queryForList("SELECT a.id FROM payment_attempt a JOIN outbox_event e ON e.payment_attempt_id=a.id AND e.event_type='PAYMENT_RESULT_RECORDED' WHERE a.id>? AND a.id<=? AND e.state='READY' AND e.next_attempt_at<=? ORDER BY a.id LIMIT 20", Long.class, cursor, upper, now);
    }

    public List<Long> waitingTierCandidates(long cursor) {
        return jdbc.queryForList("SELECT DISTINCT w.tier_id FROM waitlist_entry w JOIN ticket_tier t ON t.id=w.tier_id JOIN event_session s ON s.id=w.session_id WHERE w.status='WAITING' AND w.tier_id>? AND (s.booking_close_at<=UTC_TIMESTAMP(3) OR (s.state='OPEN' AND t.available>0)) ORDER BY w.tier_id LIMIT 20", Long.class, cursor);
    }
}
