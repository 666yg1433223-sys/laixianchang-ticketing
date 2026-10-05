package com.ticketing.booking.service;

import com.ticketing.booking.model.vo.EventVO;
import com.ticketing.booking.model.vo.EventDetailVO;
import com.ticketing.booking.model.vo.PageVO;
import com.ticketing.booking.model.vo.SessionTiersVO;
import com.ticketing.booking.model.vo.SessionVO;
import com.ticketing.booking.model.vo.TierVO;

/** Public catalogue reads and administrator mutations with durable cache invalidation. */
public interface CatalogueService {
    PageVO<EventVO> events(int page, int size);
    EventDetailVO event(long eventId);
    SessionTiersVO tiers(long sessionId);
    PageVO<EventDetailVO> adminEvents(long user, int page, int size);
    EventVO createEvent(long user, String raw);
    EventVO editEvent(long user, long eventId, String raw);
    SessionVO createSession(long user, String raw);
    SessionVO editSession(long user, long sessionId, String raw);
    TierVO createTier(long user, String raw);
    TierVO editTier(long user, long tierId, String raw);
    SessionVO state(long user, long sessionId, String action, String raw);
}
