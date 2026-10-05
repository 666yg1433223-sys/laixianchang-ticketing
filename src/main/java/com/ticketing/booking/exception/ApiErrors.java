package com.ticketing.booking.exception;

import com.ticketing.booking.domain.ApiFailure;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiErrors {
    private final com.ticketing.booking.config.Diagnostics diagnostics;
    public ApiErrors(com.ticketing.booking.config.Diagnostics diagnostics) { this.diagnostics=diagnostics; }
    @ExceptionHandler(ApiFailure.class)
    ResponseEntity<Map<String,Object>> expected(ApiFailure failure) {
        if (failure.status()==503) { diagnostics.record(failure); }
        var builder=ResponseEntity.status(failure.status());
        if (failure.retryAfter()>0) { builder.header("Retry-After",Integer.toString(failure.retryAfter())); }
        return builder.body(error(failure.code()));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class,org.springframework.web.HttpMediaTypeNotSupportedException.class})
    ResponseEntity<Map<String,Object>> invalid(Exception ignored) { return ResponseEntity.badRequest().body(error("INVALID_ARGUMENT")); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String,Object>> unavailable(Exception ignored) {
        diagnostics.record(ignored);
        return ResponseEntity.status(503).body(error("DEPENDENCY_UNAVAILABLE"));
    }
    private static Map<String,Object> error(String code) {
        String message = code.equals("CLOSE_RESULT_UNKNOWN")
            ? "关闭结果尚未确认，请查询本人订单或按原订单重试；不要认定取消失败。"
            : code.equals("PAYMENT_ACCEPTANCE_UNKNOWN") ? "模拟付款受理结果尚未确认，请沿原幂等键重试或按原尝试 ID 查询，勿换键。" : code;
        return Map.of("code",code,"message",message,"traceId",UUID.randomUUID().toString());
    }
}
