package com.ticketing.booking.model.po;

public record TierPO(long id, long sessionId, String name, String areaLabel, int capacity,
                     int available, int unitPriceCent, String currency) { }
