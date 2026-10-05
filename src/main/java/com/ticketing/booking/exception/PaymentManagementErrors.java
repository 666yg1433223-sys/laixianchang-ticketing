package com.ticketing.booking.exception;

import com.ticketing.booking.controller.PaymentManagementController;

import com.ticketing.booking.config.Diagnostics;
import com.ticketing.booking.domain.ApiFailure;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = PaymentManagementController.class)
public class PaymentManagementErrors {
    private static final Set<String> CODES = Set.of("UNAUTHENTICATED", "SIM_MANAGEMENT_FORBIDDEN",
            "RESOURCE_NOT_FOUND", "INVALID_ARGUMENT", "IDEMPOTENCY_CONFLICT",
            "DEPENDENCY_UNAVAILABLE", "OPERATION_ACCEPTANCE_UNKNOWN");
    private final Diagnostics diagnostics;

    public PaymentManagementErrors(Diagnostics diagnostics) { this.diagnostics = diagnostics; }

    @ExceptionHandler(ApiFailure.class)
    ResponseEntity<Map<String, Object>> expected(ApiFailure failure) {
        String code = CODES.contains(failure.code()) ? failure.code() : "DEPENDENCY_UNAVAILABLE";
        int status = CODES.contains(failure.code()) ? failure.status() : 503;
        return fail(status, code);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, HttpMediaTypeNotSupportedException.class})
    ResponseEntity<Map<String, Object>> invalid(Exception ignored) { return fail(400, "INVALID_ARGUMENT"); }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unavailable(Exception ignored) { return fail(503, "DEPENDENCY_UNAVAILABLE"); }

    private ResponseEntity<Map<String, Object>> fail(int status, String code) {
        diagnostics.record(new ApiFailure(status, code));
        return ResponseEntity.status(status).body(error(code));
    }

    public static Map<String, Object> error(String code) {
        String message = "OPERATION_ACCEPTANCE_UNKNOWN".equals(code)
                ? "操作受理结果尚未确认，请沿原幂等键重试或查询原操作，勿换键。" : code;
        return Map.of("code", code, "message", message, "traceId", UUID.randomUUID().toString());
    }
}
