package com.ticketing.booking.model.dto;

/** Client credentials; roles and user identifiers are always resolved by the server. */
public record AuthLoginDTO(String phone, String code) { }
