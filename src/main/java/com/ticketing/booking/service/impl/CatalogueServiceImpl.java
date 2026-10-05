package com.ticketing.booking.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.booking.catalogue.CatalogueCache;
import com.ticketing.booking.catalogue.CatalogueInvalidator;
import com.ticketing.booking.config.RuntimeServices;
import com.ticketing.booking.domain.ApiFailure;
import com.ticketing.booking.domain.DatabaseTimeSource;
import com.ticketing.booking.model.po.EventPO;
import com.ticketing.booking.model.po.SessionPO;
import com.ticketing.booking.model.po.TierPO;
import com.ticketing.booking.model.vo.EventVO;
import com.ticketing.booking.model.vo.EventDetailVO;
import com.ticketing.booking.model.vo.PageVO;
import com.ticketing.booking.model.vo.SessionTiersVO;
import com.ticketing.booking.model.vo.SessionVO;
import com.ticketing.booking.model.vo.TierVO;
import com.ticketing.booking.repository.CatalogueRepository;
import com.ticketing.booking.service.AuthService;
import com.ticketing.booking.service.CatalogueService;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.ticketing.booking.domain.Values.time;
import static com.ticketing.booking.catalogue.CatalogueInput.*;

/** Catalogue rules and mutation transactions. Database access lives in CatalogueRepository. */
@Service
@Profile("local-sim")
public class CatalogueServiceImpl implements CatalogueService {
    private static final Set<String> EVENT = Set.of("title", "description", "venueText");
    private static final Set<String> SESSION = Set.of("eventId", "startAt", "bookingOpenAt", "bookingCloseAt");
    private static final Set<String> SESSION_EDIT = Set.of("startAt", "bookingOpenAt", "bookingCloseAt");
    private static final Set<String> TIER = Set.of("sessionId", "name", "areaLabel", "capacity", "unitPriceCent");
    private static final Set<String> TIER_EDIT = Set.of("name", "areaLabel", "capacity", "unitPriceCent");
    private static final String AVAILABILITY_NOTICE = "余量仅供参考，实际库存、停售时间及每人限额以受理后的订单结果为准。";
    private final CatalogueRepository catalogue;
    private final RuntimeServices runtime;
    private final DatabaseTimeSource clock;
    private final AuthService auth;
    private final CatalogueCache cache;
    private final CatalogueInvalidator invalidator;
    private final TransactionTemplate tx;
    private final ObjectMapper json = new ObjectMapper();

    public CatalogueServiceImpl(CatalogueRepository catalogue, RuntimeServices runtime, DatabaseTimeSource clock,
                                AuthService auth, CatalogueCache cache, CatalogueInvalidator invalidator,
                                PlatformTransactionManager manager) {
        this.catalogue = catalogue;
        this.runtime = runtime;
        this.clock = clock;
        this.auth = auth;
        this.cache = cache;
        this.invalidator = invalidator;
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(2);
        tx.setTimeout(8);
    }

    @Override
    public PageVO<EventVO> events(int page, int size) {
        return cache.boundedDatabase(() -> {
            List<EventPO> rows = catalogue.findPublicEvents(size + 1, (page - 1) * size);
            return new PageVO<>(rows.stream().limit(size).map(this::eventView).toList(), page, size, rows.size() > size);
        });
    }

    @Override
    public EventDetailVO event(long eventId) {
        return cache.event(eventId, () -> {
            EventPO row = catalogue.findPublicEvent(eventId);
            if (row == null) { return null; }
            return eventDetail(row, catalogue.findPublicSessions(eventId).stream().map(this::sessionView).toList());
        });
    }

    @Override
    public SessionTiersVO tiers(long sessionId) {
        return cache.tiers(sessionId, () -> {
            SessionPO row = catalogue.findPublicSession(sessionId);
            if (row == null) { return null; }
            SessionVO session = sessionView(row);
            return new SessionTiersVO(session.sessionId(), session.eventId(), session.state(), session.startAt(),
                session.bookingOpenAt(), session.bookingCloseAt(),
                catalogue.findTiers(sessionId).stream().map(this::tierView).toList(), AVAILABILITY_NOTICE);
        });
    }

    @Override
    public PageVO<EventDetailVO> adminEvents(long user, int page, int size) {
        auth.requireAdmin(user);
        List<EventPO> rows = catalogue.findAdminEvents(size + 1, (page - 1) * size);
        List<EventDetailVO> items = rows.stream().limit(size).map(row -> eventDetail(row,
            catalogue.findAdminSessions(row.id()).stream().map(session ->
                sessionView(session, catalogue.findTiers(session.id()).stream().map(this::tierView).toList())).toList()
        )).toList();
        return new PageVO<>(items, page, size, rows.size() > size);
    }

    @Override
    public EventVO createEvent(long user, String raw) {
        auth.requireAdmin(user);
        var input = eventFields(body(raw, EVENT, Set.of("title")), true);
        return mutate(() -> {
            long id = catalogue.insertEvent(input.title(), input.description(), input.venueText());
            return changed(id, null, eventView(fact(catalogue.findEvent(id))));
        });
    }

    @Override
    public EventVO editEvent(long user, long eventId, String raw) {
        auth.requireAdmin(user);
        var input = body(raw, EVENT, Set.of());
        if (input.isEmpty()) { throw invalid(); }
        return mutate(() -> {
            EventPO row = required(catalogue.lockEvent(eventId));
            var fields = eventFields(input, false);
            String title = fields.title() != null ? fields.title() : row.title();
            String description = fields.description() != null ? fields.description() : row.description();
            String venue = fields.venueText() != null ? fields.venueText() : row.venueText();
            catalogue.updateEvent(eventId, title, description, venue);
            return changed(eventId, null, eventView(fact(catalogue.findEvent(eventId))));
        });
    }

    @Override
    public SessionVO createSession(long user, String raw) {
        auth.requireAdmin(user);
        var input = sessionFields(body(raw, SESSION, SESSION));
        return mutate(() -> {
            required(catalogue.lockEvent(input.eventId()));
            window(input.bookingOpenAt(), input.bookingCloseAt(), input.startAt(), runtime.afterLocks(clock));
            if (catalogue.countSessions(input.eventId()) >= 100) { throw new ApiFailure(409, "CATALOGUE_LIMIT_REACHED"); }
            long sessionId = catalogue.insertSession(input.eventId(), input.startAt(), input.bookingOpenAt(), input.bookingCloseAt());
            return changed(input.eventId(), sessionId, sessionView(fact(catalogue.findSession(sessionId))));
        });
    }

    @Override
    public SessionVO editSession(long user, long sessionId, String raw) {
        auth.requireAdmin(user);
        var input = body(raw, SESSION_EDIT, Set.of());
        if (input.isEmpty()) { throw invalid(); }
        return mutate(() -> {
            SessionPO row = required(catalogue.lockSession(sessionId));
            draft(row);
            var fields = sessionFields(input);
            Instant start = fields.startAt() != null ? fields.startAt() : row.startAt();
            Instant open = fields.bookingOpenAt() != null ? fields.bookingOpenAt() : row.bookingOpenAt();
            Instant close = fields.bookingCloseAt() != null ? fields.bookingCloseAt() : row.bookingCloseAt();
            window(open, close, start, runtime.afterLocks(clock));
            catalogue.updateSessionWindow(sessionId, start, open, close);
            return changed(row.eventId(), sessionId, sessionView(fact(catalogue.findSession(sessionId))));
        });
    }

    @Override
    public TierVO createTier(long user, String raw) {
        auth.requireAdmin(user);
        var input = tierFields(body(raw, TIER, Set.of("sessionId", "name", "capacity", "unitPriceCent")), true);
        return mutate(() -> {
            SessionPO session = required(catalogue.lockSession(input.sessionId()));
            draft(session);
            if (catalogue.countTiers(input.sessionId()) >= 100) { throw new ApiFailure(409, "CATALOGUE_LIMIT_REACHED"); }
            long tierId = catalogue.insertTier(input.sessionId(), input.name(), input.areaLabel(), input.capacity(), input.unitPriceCent());
            return changed(session.eventId(), input.sessionId(), tierView(fact(catalogue.findTier(tierId))));
        });
    }

    @Override
    public TierVO editTier(long user, long tierId, String raw) {
        auth.requireAdmin(user);
        var input = body(raw, TIER_EDIT, Set.of());
        if (input.isEmpty()) { throw invalid(); }
        return mutate(() -> {
            long sessionId = required(catalogue.findTierSessionId(tierId));
            // Always lock the session before its tier, matching booking and publication ordering.
            SessionPO session = required(catalogue.lockSession(sessionId));
            TierPO row = required(catalogue.lockTier(tierId, sessionId));
            if (input.has("capacity") || input.has("unitPriceCent")) { draft(session); }
            var fields = tierFields(input, false);
            String name = fields.name() != null ? fields.name() : row.name();
            String area = fields.areaLabel() != null ? fields.areaLabel() : row.areaLabel();
            int capacity = fields.capacity() != null ? fields.capacity() : row.capacity();
            int price = fields.unitPriceCent() != null ? fields.unitPriceCent() : row.unitPriceCent();
            int available = "DRAFT".equals(session.state()) ? capacity : row.available();
            catalogue.updateTier(tierId, name, area, capacity, available, price);
            return changed(session.eventId(), sessionId, tierView(fact(catalogue.findTier(tierId))));
        });
    }

    @Override
    public SessionVO state(long user, long sessionId, String action, String raw) {
        auth.requireAdmin(user);
        body(raw, Set.of(), Set.of());
        if (!Set.of("publish", "pause", "resume").contains(action)) { throw invalid(); }
        return mutate(() -> {
            SessionPO row = required(catalogue.lockSession(sessionId));
            String before = row.state();
            String after = action.equals("pause") ? "PAUSED" : "OPEN";
            if (action.equals("publish")) {
                if (!before.equals("DRAFT")) { throw new ApiFailure(409, "SESSION_ALREADY_PUBLISHED"); }
                if (catalogue.countPublishableTiers(sessionId) == 0) { throw new ApiFailure(409, "TICKET_TIER_REQUIRED"); }
            } else if (action.equals("pause") && !before.equals("OPEN") || action.equals("resume") && !before.equals("PAUSED")) {
                throw new ApiFailure(409, "SESSION_STATE_CONFLICT");
            }
            window(row.bookingOpenAt(), row.bookingCloseAt(), row.startAt(), runtime.afterLocks(clock));
            catalogue.transitionSession(sessionId, before, after);
            return changed(row.eventId(), sessionId, sessionView(fact(catalogue.findSession(sessionId))));
        });
    }

    private record Change<T>(long outboxId, T result) { }
    private record InvalidationPayload(String eventId, String sessionId) { }

    private <T> T mutate(Supplier<Change<T>> work) {
        Change<T> done = tx.execute(status -> work.get());
        // The edit and invalidation fact commit together. Cache failure is recovered from the outbox.
        invalidator.deliver(done.outboxId());
        return done.result();
    }

    private <T> Change<T> changed(long eventId, Long sessionId, T result) {
        Instant now = runtime.afterLocks(clock);
        long mutationId = catalogue.insertMutation(eventId, sessionId, now);
        String payload;
        try { payload = json.writeValueAsString(new InvalidationPayload(Long.toString(eventId), sessionId == null ? null : Long.toString(sessionId))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
        return new Change<>(catalogue.insertInvalidation(mutationId, payload, now), result);
    }

    private static <T> T required(T value) {
        if (value == null) { throw new ApiFailure(404, "RESOURCE_NOT_FOUND"); }
        return value;
    }

    private static <T> T fact(T value) {
        if (value == null) { throw new IllegalStateException("MISSING_FACT"); }
        return value;
    }

    private static void draft(SessionPO session) {
        if (!"DRAFT".equals(session.state())) { throw new ApiFailure(409, "PUBLISHED_FIELDS_FROZEN"); }
    }

    private EventVO eventView(EventPO row) {
        return new EventVO(Long.toString(row.id()), row.title(), Objects.toString(row.description(), ""), row.venueText());
    }

    private EventDetailVO eventDetail(EventPO row, List<SessionVO> sessions) {
        EventVO event = eventView(row);
        return new EventDetailVO(event.eventId(), event.title(), event.description(), event.venueText(), sessions);
    }

    private SessionVO sessionView(SessionPO row) { return sessionView(row, null); }

    private SessionVO sessionView(SessionPO row, List<TierVO> ticketTiers) {
        return new SessionVO(Long.toString(row.id()), Long.toString(row.eventId()), row.state(), time(row.startAt()),
            time(row.bookingOpenAt()), time(row.bookingCloseAt()), ticketTiers);
    }

    private TierVO tierView(TierPO row) {
        return new TierVO(Long.toString(row.id()), Long.toString(row.sessionId()), row.name(), row.areaLabel(),
            row.unitPriceCent(), row.currency(), row.available(), row.capacity());
    }
}
