package com.ticketing.booking.repository;

import com.ticketing.booking.model.po.CatalogueInvalidationPO;
import com.ticketing.booking.model.po.EventPO;
import com.ticketing.booking.model.po.SessionPO;
import com.ticketing.booking.model.po.TierPO;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import static com.ticketing.booking.domain.Values.*;

/** Catalogue storage operations; caller-owned transactions preserve the session-before-tier lock order. */
@Repository
public class CatalogueRepository {
    private static final RowMapper<EventPO> EVENT = (row, index) -> new EventPO(
        row.getLong("id"), row.getString("title"), row.getString("description"), row.getString("venue_text"));
    private static final RowMapper<SessionPO> SESSION = (row, index) -> new SessionPO(
        row.getLong("id"), row.getLong("event_id"), row.getString("state"),
        instant(row.getObject("start_at")), instant(row.getObject("booking_open_at")), instant(row.getObject("booking_close_at")));
    private static final RowMapper<TierPO> TIER = (row, index) -> new TierPO(
        row.getLong("id"), row.getLong("session_id"), row.getString("name"), row.getString("area_label"),
        row.getInt("capacity"), row.getInt("available"), row.getInt("unit_price_cent"), row.getString("currency"));
    private final JdbcTemplate jdbc;

    public CatalogueRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<EventPO> findPublicEvents(int limit, int offset) {
        return jdbc.query("SELECT e.* FROM event e WHERE EXISTS (SELECT 1 FROM event_session s WHERE s.event_id=e.id AND s.state IN ('OPEN','PAUSED')) ORDER BY e.id DESC LIMIT ? OFFSET ?", EVENT, limit, offset);
    }

    public EventPO findPublicEvent(long eventId) {
        return single(jdbc.query("SELECT e.* FROM event e WHERE e.id=? AND EXISTS (SELECT 1 FROM event_session s WHERE s.event_id=e.id AND s.state IN ('OPEN','PAUSED'))", EVENT, eventId));
    }

    public List<SessionPO> findPublicSessions(long eventId) {
        return jdbc.query("SELECT * FROM event_session WHERE event_id=? AND state IN ('OPEN','PAUSED') ORDER BY start_at,id LIMIT 100", SESSION, eventId);
    }

    public SessionPO findPublicSession(long sessionId) {
        return single(jdbc.query("SELECT * FROM event_session WHERE id=? AND state IN ('OPEN','PAUSED')", SESSION, sessionId));
    }

    public List<TierPO> findTiers(long sessionId) {
        return jdbc.query("SELECT * FROM ticket_tier WHERE session_id=? ORDER BY id LIMIT 100", TIER, sessionId);
    }

    public List<EventPO> findAdminEvents(int limit, int offset) {
        return jdbc.query("SELECT * FROM event ORDER BY id DESC LIMIT ? OFFSET ?", EVENT, limit, offset);
    }

    public List<SessionPO> findAdminSessions(long eventId) {
        return jdbc.query("SELECT * FROM event_session WHERE event_id=? ORDER BY id LIMIT 100", SESSION, eventId);
    }

    public long insertEvent(String title, String description, String venueText) {
        return insert("INSERT INTO event(title,description,venue_text) VALUES(?,?,?)", title, description, venueText);
    }

    public EventPO findEvent(long eventId) {
        return single(jdbc.query("SELECT * FROM event WHERE id=?", EVENT, eventId));
    }

    public EventPO lockEvent(long eventId) {
        return single(jdbc.query("SELECT * FROM event WHERE id=? FOR UPDATE", EVENT, eventId));
    }

    public void updateEvent(long eventId, String title, String description, String venueText) {
        jdbc.update("UPDATE event SET title=?,description=?,venue_text=?,updated_at=CURRENT_TIMESTAMP(3) WHERE id=?", title, description, venueText, eventId);
    }

    public long countSessions(long eventId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM event_session WHERE event_id=?", Long.class, eventId);
    }

    public long insertSession(long eventId, Instant startAt, Instant openAt, Instant closeAt) {
        return insert("INSERT INTO event_session(event_id,start_at,booking_open_at,booking_close_at,state) VALUES(?,?,?,?,'DRAFT')", eventId, sqlTime(startAt), sqlTime(openAt), sqlTime(closeAt));
    }

    public SessionPO findSession(long sessionId) {
        return single(jdbc.query("SELECT * FROM event_session WHERE id=?", SESSION, sessionId));
    }

    public SessionPO lockSession(long sessionId) {
        return single(jdbc.query("SELECT * FROM event_session WHERE id=? FOR UPDATE", SESSION, sessionId));
    }

    public void updateSessionWindow(long sessionId, Instant startAt, Instant openAt, Instant closeAt) {
        jdbc.update("UPDATE event_session SET start_at=?,booking_open_at=?,booking_close_at=? WHERE id=?", sqlTime(startAt), sqlTime(openAt), sqlTime(closeAt), sessionId);
    }

    public long countTiers(long sessionId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ticket_tier WHERE session_id=?", Long.class, sessionId);
    }

    public long insertTier(long sessionId, String name, String area, int capacity, int price) {
        return insert("INSERT INTO ticket_tier(session_id,name,area_label,capacity,available,unit_price_cent,currency) VALUES(?,?,?,?,?,?,'CNY')", sessionId, name, area, capacity, capacity, price);
    }

    public TierPO findTier(long tierId) {
        return single(jdbc.query("SELECT * FROM ticket_tier WHERE id=?", TIER, tierId));
    }

    public Long findTierSessionId(long tierId) {
        return single(jdbc.queryForList("SELECT session_id FROM ticket_tier WHERE id=?", Long.class, tierId));
    }

    public TierPO lockTier(long tierId, long sessionId) {
        return single(jdbc.query("SELECT * FROM ticket_tier WHERE id=? AND session_id=? FOR UPDATE", TIER, tierId, sessionId));
    }

    public void updateTier(long tierId, String name, String area, int capacity, int available, int price) {
        jdbc.update("UPDATE ticket_tier SET name=?,area_label=?,capacity=?,available=?,unit_price_cent=? WHERE id=?", name, area, capacity, available, price, tierId);
    }

    public long countPublishableTiers(long sessionId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ticket_tier WHERE session_id=? AND capacity>0 AND available=capacity AND unit_price_cent>0", Long.class, sessionId);
    }

    public void transitionSession(long sessionId, String before, String after) {
        one(jdbc.update("UPDATE event_session SET state=? WHERE id=? AND state=?", after, sessionId, before));
    }

    public long insertMutation(long eventId, Long sessionId, Instant now) {
        return insert("INSERT INTO catalogue_mutation(event_id,event_session_id,created_at) VALUES(?,?,?)", eventId, sessionId, sqlTime(now));
    }

    public long insertInvalidation(long mutationId, String payload, Instant now) {
        return insert("INSERT INTO outbox_event(event_type,aggregate_id,schema_version,payload,state,attempts,next_attempt_at,created_at,catalogue_mutation_id) VALUES('CATALOG_INVALIDATE',?,1,?,'READY',0,?,?,?)", mutationId, payload, sqlTime(now), sqlTime(now), mutationId);
    }

    public List<Long> findDueInvalidations() {
        return jdbc.queryForList("SELECT id FROM outbox_event WHERE event_type='CATALOG_INVALIDATE' AND state='READY' AND next_attempt_at<=CURRENT_TIMESTAMP(3) ORDER BY id LIMIT 20", Long.class);
    }

    public CatalogueInvalidationPO findReadyInvalidation(long outboxId) {
        return single(jdbc.query("SELECT e.id,e.attempts,e.recovery_generation,m.event_id,m.event_session_id FROM outbox_event e JOIN catalogue_mutation m ON m.id=e.catalogue_mutation_id AND m.id=e.aggregate_id WHERE e.id=? AND e.event_type='CATALOG_INVALIDATE' AND e.schema_version=1 AND e.state='READY'", (row, index) -> {
            long sessionId = row.getLong("event_session_id");
            Long optionalSession = row.wasNull() ? null : sessionId;
            return new CatalogueInvalidationPO(row.getLong("id"), row.getLong("attempts"),
                row.getLong("recovery_generation"), row.getLong("event_id"), optionalSession);
        }, outboxId));
    }

    public void completeInvalidation(long outboxId, long generation) {
        jdbc.update("UPDATE outbox_event SET state='DONE',next_attempt_at=NULL,last_error_code=NULL,finished_at=CURRENT_TIMESTAMP(3) WHERE id=? AND event_type='CATALOG_INVALIDATE' AND state='READY' AND recovery_generation=?", outboxId, generation);
    }

    public void recordInvalidationFailure(long outboxId, long attempts, long generation, long nextAttempts,
                                          String state, Instant retryAt, Instant finishedAt) {
        jdbc.update("UPDATE outbox_event SET attempts=?,state=?,next_attempt_at=?,finished_at=?,last_error_code='CACHE_DELETE_FAILED' WHERE id=? AND event_type='CATALOG_INVALIDATE' AND state='READY' AND attempts=? AND recovery_generation=?",
            nextAttempts, state, retryAt == null ? null : sqlTime(retryAt), finishedAt == null ? null : sqlTime(finishedAt), outboxId, attempts, generation);
    }

    private static <T> T single(List<T> rows) {
        if (rows.size() > 1) { throw new IllegalStateException("MULTIPLE_ROWS"); }
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long insert(String sql, Object... args) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        int changed = jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            statement.setQueryTimeout(5);
            for (int i = 0; i < args.length; i++) { statement.setObject(i + 1, args[i]); }
            return statement;
        }, key);
        one(changed);
        if (key.getKey() == null) { throw new IllegalStateException("MISSING_GENERATED_ID"); }
        return key.getKey().longValue();
    }
}
