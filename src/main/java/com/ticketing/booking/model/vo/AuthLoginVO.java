package com.ticketing.booking.model.vo;

public record AuthLoginVO(String token, String tokenType, long expiresInSeconds, UserVO user) { }
