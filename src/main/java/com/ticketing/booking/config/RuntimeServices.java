package com.ticketing.booking.config;

import com.ticketing.booking.auth.PrincipalResolver;
import com.ticketing.booking.domain.DatabaseClock;
import com.ticketing.booking.service.ExecutionObserver;

public interface RuntimeServices extends PrincipalResolver, DatabaseClock, ExecutionObserver {
    record SimulationPlan(String result, long delayMillis) {
        public SimulationPlan {
            if (!java.util.Set.of("SUCCESS","FAILED").contains(result) || delayMillis<0 || delayMillis>1_200_000) {
                throw new IllegalArgumentException("INVALID_SIMULATION_PLAN");
            }
        }
    }
    default boolean simulatorEnabled() { return false; }
    default SimulationPlan simulationPlan() { throw new IllegalStateException("SIMULATOR_DISABLED"); }
}
