package com.ticketing.booking.model.vo;

/** Public identity, deliberately excluding the stored phone number. */
public record UserVO(String id, String nickname, String role) { }
