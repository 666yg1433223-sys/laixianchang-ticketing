package com.ticketing.booking.model.vo;

import java.util.List;

public record SessionTiersVO(String sessionId, String eventId, String state, String startAt,
                            String bookingOpenAt, String bookingCloseAt,
                            List<TierVO> items, String availabilityNotice) { }
