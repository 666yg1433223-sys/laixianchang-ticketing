package com.ticketing.booking.config;

import com.ticketing.booking.auth.PrincipalResolver;
import com.ticketing.booking.domain.DatabaseClock;
import com.ticketing.booking.service.ExecutionObserver;

public interface RuntimeServices extends PrincipalResolver, DatabaseClock, ExecutionObserver {
    /** Ordinary product mode is separate from the isolated fixture runtime. */
    default boolean productEnabled() { return false; }
    default boolean settlementRecoveryEnabled() { return recoveryEnabled(); }

    record ManagementPrincipal(long operatorId, boolean admin, boolean operator) { }

    default ManagementPrincipal authenticateManagement(String authorization) {
        return new ManagementPrincipal(authenticate(authorization), false, false);
    }
    default java.util.Set<Long> managementAttempts() { return java.util.Set.of(); }
    default boolean managementReady() { return false; }

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
