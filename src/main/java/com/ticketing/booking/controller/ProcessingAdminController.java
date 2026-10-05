package com.ticketing.booking.controller;

import com.ticketing.booking.model.dto.*;
import com.ticketing.booking.model.vo.*;
import com.ticketing.booking.validation.InputValidation;

import com.ticketing.booking.domain.ApiFailure;
import com.ticketing.booking.model.dto.ProcessingRetryDTO;
import com.ticketing.booking.service.AuthService;
import com.ticketing.booking.service.ProcessingRecoveryService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import static com.ticketing.booking.domain.Values.id;

@RestController
@Profile("local-sim")
@RequestMapping("/v1/admin/processing-failures")
public class ProcessingAdminController {
    private final ProcessingRecoveryService service;
    private final AuthService auth;
    public ProcessingAdminController(ProcessingRecoveryService service, AuthService auth) {
        this.service = service;
        this.auth = auth;
    }
    @GetMapping
    public ProcessingFailuresVO list(HttpServletRequest request, @RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int size) {
        return service.list(actor(request), page, size);
    }
    @PostMapping(value="/{eventId}/retry", consumes="application/json")
    public ProcessingRetryResultVO retry(HttpServletRequest request, @PathVariable String eventId,
            @RequestHeader(value="Idempotency-Key", required=false) String key, @RequestBody String body) {
        long operator = actor(request);
        // Preserve authorization before input validation for this administrator endpoint.
        auth.requireAdmin(operator);
        InputValidation.key(key);
        long id = id(eventId);
        var node = InputValidation.body(body, Set.of("reason"));
        if (!node.path("reason").isTextual()) { throw new ApiFailure(400, "INVALID_ARGUMENT"); }
        return service.retry(operator, new ProcessingRetryDTO(id, key, node.path("reason").asText().trim()));
    }
    @GetMapping("/operations/{operationId}")
    public PaymentOperationVO operation(HttpServletRequest request, @PathVariable String operationId) {
        return service.operation(actor(request), operationId);
    }
    private static long actor(HttpServletRequest request) {
        return (Long) request.getAttribute("booking.principal");
    }
}
