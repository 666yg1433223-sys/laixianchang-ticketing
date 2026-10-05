package com.ticketing.booking.model.dto;

/** Validated tier fields. Server-owned availability and currency are not accepted as input. */
public record CatalogueTierDTO(Long sessionId, String name, String areaLabel, Integer capacity, Integer unitPriceCent) { }
