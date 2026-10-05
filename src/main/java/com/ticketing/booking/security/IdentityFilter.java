package com.ticketing.booking.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.booking.config.RuntimeServices;
import com.ticketing.booking.domain.ApiFailure;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class IdentityFilter extends OncePerRequestFilter {
    private final RuntimeServices runtime;
    public IdentityFilter(RuntimeServices runtime) { this.runtime=runtime; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        String path = request.getRequestURI();
        if (path.startsWith("/v1/booking-requests") || path.startsWith("/v1/orders")
                || path.startsWith("/v1/payment-attempts") || path.startsWith("/v1/waitlist") || path.startsWith("/v1/refunds")
                || path.startsWith("/v1/admin") || path.equals("/v1/auth/me") || path.equals("/v1/auth/logout")) {
            try {
                long principal = runtime.authenticate(request.getHeader("Authorization"));
                if (principal <= 0) { throw new ApiFailure(401, "UNAUTHENTICATED"); }
                request.setAttribute("booking.principal", principal);
            }
            catch (ApiFailure failure) {
                reject(response, failure.status(), failure.code(), failure.retryAfter());
                return;
            }
            catch (IllegalArgumentException denied) {
                reject(response, 401, "UNAUTHENTICATED", 0);
                return;
            }
            catch (RuntimeException dependency) {
                reject(response, 503, "DEPENDENCY_UNAVAILABLE", 0);
                return;
            }
        }
        chain.doFilter(request,response);
    }

    private static void reject(HttpServletResponse response, int status, String code, int retryAfter) throws IOException {
        response.setStatus(status); response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        if (retryAfter > 0) { response.setHeader("Retry-After", Integer.toString(retryAfter)); }
        new ObjectMapper().writeValue(response.getOutputStream(), Map.of("code", code, "message", code,
            "traceId", UUID.randomUUID().toString()));
    }
}
