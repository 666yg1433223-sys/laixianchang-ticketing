package com.ticketing.booking.api;

import com.ticketing.booking.config.RuntimeServices;
import com.ticketing.booking.domain.ApiFailure;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IdentityFilterOfflineTest {
    @Test void allPersonalAndAdminEntryPointsRequireIdentity() throws Exception {
        for (String path : new String[]{"/v1/orders", "/v1/booking-requests/1", "/v1/payment-attempts/1",
                "/v1/waitlist", "/v1/admin/events", "/v1/auth/me", "/v1/auth/logout"}) {
            var runtime = mock(RuntimeServices.class);
            when(runtime.authenticate(null)).thenThrow(new ApiFailure(401, "UNAUTHENTICATED"));
            var response = new MockHttpServletResponse();
            var chain = new MockFilterChain();
            new IdentityFilter(runtime).doFilter(new MockHttpServletRequest("GET", path), response, chain);
            assertEquals(401, response.getStatus(), path);
            assertNull(chain.getRequest());
        }
    }

    @Test void publicCatalogueAuthCodeAndStaticAssetsDoNotRequireToken() throws Exception {
        var runtime = mock(RuntimeServices.class);
        for (String path : new String[]{"/", "/app.js", "/v1/events", "/v1/events/1", "/v1/sessions/2/ticket-tiers",
                "/v1/auth/code", "/v1/auth/login"}) {
            var chain = new MockFilterChain();
            new IdentityFilter(runtime).doFilter(new MockHttpServletRequest("GET", path), new MockHttpServletResponse(), chain);
            assertNotNull(chain.getRequest(), path);
        }
        verifyNoInteractions(runtime);
    }

    @Test void principalIsServerResolvedNotTakenFromAnAccountHeader() throws Exception {
        var runtime = mock(RuntimeServices.class);
        when(runtime.authenticate("Bearer value")).thenReturn(42L);
        var request = new MockHttpServletRequest("GET", "/v1/orders");
        request.addHeader("Authorization", "Bearer value");
        request.addHeader("X-User-Id", "999"); request.addHeader("X-Role", "ADMIN");
        var chain = new MockFilterChain();
        new IdentityFilter(runtime).doFilter(request, new MockHttpServletResponse(), chain);
        assertEquals(42L, request.getAttribute("booking.principal"));
        assertNotNull(chain.getRequest());
    }

    @Test void dependencyFailureIs503NotAFalseLogoutAndErrorsAreSanitized() throws Exception {
        for (RuntimeException failure : new RuntimeException[]{new ApiFailure(503, "DEPENDENCY_UNAVAILABLE"),
                new IllegalStateException("secret connection detail")}) {
            var runtime = mock(RuntimeServices.class); when(runtime.authenticate(null)).thenThrow(failure);
            var response = new MockHttpServletResponse();
            new IdentityFilter(runtime).doFilter(new MockHttpServletRequest("GET", "/v1/orders"), response, new MockFilterChain());
            assertEquals(503, response.getStatus());
            assertFalse(response.getContentAsString().contains("secret"));
        }
    }

    @Test void oldFixtureExceptionStillMeansUnauthenticated() throws Exception {
        var runtime = mock(RuntimeServices.class);
        when(runtime.authenticate(null)).thenThrow(new IllegalArgumentException("fixture token invalid"));
        var response = new MockHttpServletResponse();
        new IdentityFilter(runtime).doFilter(new MockHttpServletRequest("GET", "/v1/orders"), response, new MockFilterChain());
        assertEquals(401, response.getStatus());
    }
}
