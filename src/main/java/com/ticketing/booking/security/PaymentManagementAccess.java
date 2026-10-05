package com.ticketing.booking.security;

import com.ticketing.booking.config.Bootstrap;
import com.ticketing.booking.config.RuntimeServices;
import com.ticketing.booking.domain.ApiFailure;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class PaymentManagementAccess {
    public record Operator(long id, Set<Long> attemptIds) {
        public Operator {
            attemptIds = Set.copyOf(attemptIds);
            if (id <= 0 || attemptIds.stream().anyMatch(attemptId -> attemptId <= 0)) {
                throw new IllegalArgumentException("INVALID_MANAGEMENT_SCOPE");
            }
        }
    }

    private final RuntimeServices runtime;
    private final Environment environment;
    private final boolean trustedTestClasspath;

    public PaymentManagementAccess(RuntimeServices runtime, Environment environment) {
        this.runtime = runtime;
        this.environment = environment;
        this.trustedTestClasspath = hasTrustedTestClasspath(PaymentManagementAccess.class.getClassLoader());
    }

    public Operator authorize(String authorization) {
        if (authorization == null || !authorization.matches("Bearer [^\\s,\\p{Cntrl}]+")) {
            throw new ApiFailure(401, "UNAUTHENTICATED");
        }
        RuntimeServices.ManagementPrincipal principal;
        try { principal = runtime.authenticateManagement(authorization); }
        catch (IllegalArgumentException invalid) { throw new ApiFailure(401, "UNAUTHENTICATED"); }
        if (principal == null || principal.operatorId() <= 0) {
            throw new ApiFailure(401, "UNAUTHENTICATED");
        }
        if (!principal.admin() || !principal.operator() || !trustedTestClasspath
                || !Arrays.equals(environment.getActiveProfiles(), new String[]{"biz01-it"})
                || !"true".equals(environment.getProperty("biz.sim.management.enabled"))
                || !runtime.managementReady()) {
            throw new ApiFailure(403, "SIM_MANAGEMENT_FORBIDDEN");
        }
        try { return new Operator(principal.operatorId(), runtime.managementAttempts()); }
        catch (IllegalArgumentException | NullPointerException invalidScope) {
            throw new ApiFailure(403, "SIM_MANAGEMENT_FORBIDDEN");
        }
    }

    static boolean hasTrustedTestClasspath(ClassLoader loader) {
        try {
            // Inspect the fixture's origin without running its credential-loading initializer.
            Class<?> fixture = Class.forName("com.ticketing.booking.fixture.TestRuntime", false, loader);
            Path origin = Path.of(fixture.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            Path application = Path.of(com.ticketing.booking.application.BookingApplication.class
                .getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            return "classes".equals(application.getFileName().toString())
                && "target".equals(application.getParent().getFileName().toString())
                && origin.equals(application.resolveSibling("test-classes").toRealPath());
        } catch (Exception | LinkageError unavailable) { return false; }
    }
}
