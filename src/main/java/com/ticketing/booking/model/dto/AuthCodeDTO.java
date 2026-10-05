package com.ticketing.booking.model.dto;

/** The only client input accepted when requesting a simulated SMS code. */
public record AuthCodeDTO(String phone) { }
