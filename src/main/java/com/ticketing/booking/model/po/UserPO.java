package com.ticketing.booking.model.po;

/** Authentication projection of the app_user database record. Never returned to the client. */
public record UserPO(long id, String nickname, String phone, String role) { }
