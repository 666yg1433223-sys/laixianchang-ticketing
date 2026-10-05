package com.ticketing.booking.security;

import com.ticketing.booking.config.RuntimeServices;
import com.ticketing.booking.domain.ApiFailure;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentManagementAccessOfflineTest {
    private static final String AUTHORIZATION = "Bearer offline-management-operator";

    private RuntimeServices runtime(boolean admin, boolean capability) {
        RuntimeServices runtime = mock(RuntimeServices.class);
        when(runtime.authenticateManagement(AUTHORIZATION))
                .thenReturn(new RuntimeServices.ManagementPrincipal(41, admin, capability));
        when(runtime.managementReady()).thenReturn(true);
        when(runtime.managementAttempts()).thenReturn(Set.of(11L, 12L));
        return runtime;
    }

    private MockEnvironment enabled() {
        MockEnvironment environment = new MockEnvironment().withProperty("biz.sim.management.enabled", "true");
        environment.setActiveProfiles("biz01-it");
        return environment;
    }

    private static void denied(PaymentManagementAccess access, String authorization, int status, String code) {
        ApiFailure failure = assertThrows(ApiFailure.class, () -> access.authorize(authorization));
        assertEquals(status, failure.status());
        assertEquals(code, failure.code());
    }

    @Test void normalIdentityDefaultsNeverGrantManagementAuthority() {
        RuntimeServices runtime = mock(RuntimeServices.class, CALLS_REAL_METHODS);
        when(runtime.authenticate(AUTHORIZATION)).thenReturn(17L);
        assertEquals(new RuntimeServices.ManagementPrincipal(17, false, false), runtime.authenticateManagement(AUTHORIZATION));
        assertFalse(runtime.managementReady());
        assertTrue(runtime.managementAttempts().isEmpty());
        denied(new PaymentManagementAccess(runtime, enabled()), AUTHORIZATION, 403, "SIM_MANAGEMENT_FORBIDDEN");
    }

    @Test void authenticationPrecedesDisabledEnvironmentAndScopeLookup() {
        RuntimeServices runtime = runtime(true, true);
        when(runtime.authenticateManagement("Bearer unknown")).thenThrow(new IllegalArgumentException("private detail"));
        PaymentManagementAccess access = new PaymentManagementAccess(runtime, new MockEnvironment());
        for (String authorization : new String[]{null, "", "unknown", "Basic unknown", "Bearer ",
                "Bearer a,b", "Bearer a b", "Bearer a\nb", "Bearer unknown"}) {
            denied(access, authorization, 401, "UNAUTHENTICATED");
        }
        verify(runtime, never()).managementReady();
        verify(runtime, never()).managementAttempts();
    }

    @Test void roleAndCapabilityAreBothRequired() {
        for (boolean[] permissions : new boolean[][]{{false, false}, {true, false}, {false, true}}) {
            RuntimeServices runtime = runtime(permissions[0], permissions[1]);
            denied(new PaymentManagementAccess(runtime, enabled()), AUTHORIZATION, 403, "SIM_MANAGEMENT_FORBIDDEN");
            verify(runtime, never()).managementAttempts();
        }
    }

    @Test void exactProfileExplicitSwitchAndReadyManifestAreRequired() {
        for (String[] profiles : new String[][]{{}, {"biz01"}, {"biz01-it", "extra"}}) {
            RuntimeServices runtime = runtime(true, true);
            MockEnvironment environment = enabled();
            environment.setActiveProfiles(profiles);
            denied(new PaymentManagementAccess(runtime, environment), AUTHORIZATION, 403, "SIM_MANAGEMENT_FORBIDDEN");
            verify(runtime, never()).managementAttempts();
        }
        for (String switchValue : new String[]{"false", "TRUE", "1", ""}) {
            RuntimeServices runtime = runtime(true, true);
            denied(new PaymentManagementAccess(runtime, enabled().withProperty("biz.sim.management.enabled", switchValue)),
                    AUTHORIZATION, 403, "SIM_MANAGEMENT_FORBIDDEN");
            verify(runtime, never()).managementAttempts();
        }
        RuntimeServices runtime = runtime(true, true);
        MockEnvironment absentSwitch = new MockEnvironment().withProperty("biz.sim.enabled", "true");
        absentSwitch.setActiveProfiles("biz01-it");
        denied(new PaymentManagementAccess(runtime, absentSwitch), AUTHORIZATION, 403, "SIM_MANAGEMENT_FORBIDDEN");
        when(runtime.managementReady()).thenReturn(false);
        denied(new PaymentManagementAccess(runtime, enabled()), AUTHORIZATION, 403, "SIM_MANAGEMENT_FORBIDDEN");
        verify(runtime, never()).managementAttempts();
    }

    @Test void returnedScopeIsImmutableAndReadFreshForEveryAuthorization() {
        RuntimeServices runtime = runtime(true, true);
        Set<Long> manifest = new HashSet<>(Set.of(11L));
        when(runtime.managementAttempts()).thenReturn(manifest);
        PaymentManagementAccess access = new PaymentManagementAccess(runtime, enabled());
        PaymentManagementAccess.Operator first = access.authorize(AUTHORIZATION);
        assertEquals(41L, first.id());
        assertThrows(UnsupportedOperationException.class, () -> first.attemptIds().add(13L));
        manifest.add(12L);
        assertEquals(Set.of(11L), first.attemptIds());
        assertEquals(Set.of(11L, 12L), access.authorize(AUTHORIZATION).attemptIds());
        verify(runtime, times(2)).managementAttempts();
    }

    @Test void malformedManifestFailsClosedAndMissingFixtureClasspathCannotEnableAccess() {
        RuntimeServices runtime = runtime(true, true);
        when(runtime.managementAttempts()).thenReturn(Set.of(0L));
        denied(new PaymentManagementAccess(runtime, enabled()), AUTHORIZATION, 403, "SIM_MANAGEMENT_FORBIDDEN");
        when(runtime.managementAttempts()).thenReturn(null);
        denied(new PaymentManagementAccess(runtime, enabled()), AUTHORIZATION, 403, "SIM_MANAGEMENT_FORBIDDEN");
        assertFalse(PaymentManagementAccess.hasTrustedTestClasspath(new ClassLoader(null) { }));
        assertTrue(PaymentManagementAccess.hasTrustedTestClasspath(getClass().getClassLoader()));
    }
}
