package com.ticketing.booking.security;

public interface PrincipalResolver {
    long authenticate(String authorization);
}
