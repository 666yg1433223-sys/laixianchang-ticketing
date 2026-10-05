package com.ticketing.booking.model.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** ticketTiers appears only in the administrator's expanded catalogue response. */
public record SessionVO(String sessionId, String eventId, String state, String startAt,
                        String bookingOpenAt, String bookingCloseAt,
                        @JsonInclude(JsonInclude.Include.NON_NULL) List<TierVO> ticketTiers) { }
