package com.ticketing.booking.model.vo;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import java.util.Map;

/** The order embedded in booking status intentionally excludes route identifiers. */
public record BookingOrderVO(String orderId,String orderStatus,int unitPriceCent,int quantity,int payableCent,
        String currency,String holdCreatedAt,String payExpiresAt,String closedAt,String closeReason,
        String currentAttemptId,String paidAttemptId,String paidAt,
        @JsonAnyGetter Map<String,Object> productFields) { }
