package com.ticketing.booking.auth;

public interface PrincipalResolver {
    long authenticate(String authorization);
}
