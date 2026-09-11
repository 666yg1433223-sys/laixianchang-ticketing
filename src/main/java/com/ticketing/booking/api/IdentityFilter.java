package com.ticketing.booking.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.booking.config.RuntimeServices;
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
        if (request.getRequestURI().startsWith("/v1/booking-requests") || request.getRequestURI().startsWith("/v1/orders") || request.getRequestURI().startsWith("/v1/payment-attempts")) {
            try { request.setAttribute("booking.principal",runtime.authenticate(request.getHeader("Authorization"))); }
            catch (IllegalArgumentException denied) {
                response.setStatus(401); response.setContentType("application/json");
                new ObjectMapper().writeValue(response.getOutputStream(),Map.of("code","UNAUTHENTICATED","message","需要有效身份","traceId",UUID.randomUUID().toString()));
                return;
            }
        }
        chain.doFilter(request,response);
    }
}
