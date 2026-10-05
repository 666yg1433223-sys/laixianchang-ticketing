package com.ticketing.booking.model.vo;

public record BookingStatusVO(String requestId,String status,String reasonCode,String acceptedAt,
        String processDeadline,String serverTime,BookingOrderVO order) { }
