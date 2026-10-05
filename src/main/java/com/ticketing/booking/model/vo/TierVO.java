package com.ticketing.booking.model.vo;

public record TierVO(String tierId, String sessionId, String name, String areaLabel,
                     int unitPriceCent, String currency, int referenceAvailable, int capacity) { }
