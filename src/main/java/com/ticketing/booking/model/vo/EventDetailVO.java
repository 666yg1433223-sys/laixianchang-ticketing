package com.ticketing.booking.model.vo;

import java.util.List;

public record EventDetailVO(String eventId, String title, String description, String venueText,
                           List<SessionVO> sessions) { }
