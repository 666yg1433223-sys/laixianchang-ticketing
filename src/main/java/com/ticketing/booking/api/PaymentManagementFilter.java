package com.ticketing.booking.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.booking.auth.PaymentManagementAccess;
import com.ticketing.booking.config.Diagnostics;
import com.ticketing.booking.domain.ApiFailure;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class PaymentManagementFilter extends OncePerRequestFilter {
    public static final String OPERATOR_ATTRIBUTE = "payment.operator";
    private final PaymentManagementAccess access;
    private final Diagnostics diagnostics;
    private final ObjectMapper json = new ObjectMapper();

    public PaymentManagementFilter(PaymentManagementAccess access, Diagnostics diagnostics) {
        this.access = access;
        this.diagnostics = diagnostics;
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.equals("/v1/internal/sim") && !path.startsWith("/v1/internal/sim/");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        try {
            var headers = Collections.list(request.getHeaders("Authorization"));
            if (headers.size() != 1) { throw new ApiFailure(401, "UNAUTHENTICATED"); }
            request.setAttribute(OPERATOR_ATTRIBUTE, access.authorize(headers.get(0)));
            if (Collections.list(request.getHeaders("Content-Type")).size() > 1) {
                throw new ApiFailure(400, "INVALID_ARGUMENT");
            }
        } catch (ApiFailure failure) {
            deny(response, failure.status(), failure.code());
            return;
        } catch (Exception unavailable) {
            deny(response, 503, "DEPENDENCY_UNAVAILABLE");
            return;
        }
        chain.doFilter(request, response);
    }

    private void deny(HttpServletResponse response, int status, String code) throws IOException {
        diagnostics.record(new ApiFailure(status, code));
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(), PaymentManagementErrors.error(code));
    }
}
