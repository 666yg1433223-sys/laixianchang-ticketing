package com.ticketing.booking.config;

import com.ticketing.booking.auth.PrincipalResolver;
import com.ticketing.booking.domain.DatabaseClock;
import com.ticketing.booking.service.ExecutionObserver;

public interface RuntimeServices extends PrincipalResolver, DatabaseClock, ExecutionObserver {
}
