package com.ticketing.booking.model.vo;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

public record OrderListItemVO(@JsonUnwrapped OrderVO order,String eventId,String eventTitle,String tierName,String startAt) { }
