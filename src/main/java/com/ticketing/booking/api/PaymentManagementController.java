package com.ticketing.booking.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.booking.auth.PaymentManagementAccess.Operator;
import com.ticketing.booking.domain.ApiFailure;
import com.ticketing.booking.domain.Values;
import com.ticketing.booking.service.PaymentManagementService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/v1/internal/sim", produces = "application/json")
public class PaymentManagementController {
    private final PaymentManagementService service;
    private final ObjectMapper json = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    public PaymentManagementController(PaymentManagementService service) { this.service = service; }

    @GetMapping("/payment-failures")
    public Map<String, Object> list(HttpServletRequest request) {
        Operator operator = operator(request);
        noBody(request);
        parameters(request, Set.of("afterAttemptId", "limit"));
        String after = request.getParameter("afterAttemptId");
        long afterAttemptId = after == null || after.equals("0") ? 0 : Values.id(after);
        String limit = request.getParameter("limit");
        int size = 20;
        if (limit != null) {
            if (!limit.matches("[1-9][0-9]?")) { throw invalid(); }
            size = Integer.parseInt(limit);
            if (size > 50) { throw invalid(); }
        }
        return service.list(operator, afterAttemptId, size);
    }

    @PostMapping(value = "/payment-attempts/{attemptId}/recover", consumes = "application/json")
    public ResponseEntity<Map<String, Object>> recover(HttpServletRequest request,
            @PathVariable String attemptId, @RequestBody String body) {
        Operator operator = operator(request);
        parameters(request, Set.of());
        long id = Values.id(attemptId);
        String key = idempotencyKey(request);
        JsonNode input = input(body, Set.of("reason"));
        return receipt(service.recover(operator, id, key, reason(input)));
    }

    @PostMapping(value = "/payment-results/{resultId}/redeliver", consumes = "application/json")
    public ResponseEntity<Map<String, Object>> redeliver(HttpServletRequest request,
            @PathVariable String resultId, @RequestBody String body) {
        Operator operator = operator(request);
        parameters(request, Set.of());
        String id = uuid(resultId);
        String key = idempotencyKey(request);
        JsonNode input = input(body, Set.of("reason", "deliveries"));
        JsonNode deliveries = input.get("deliveries");
        if (!deliveries.isIntegralNumber() || !deliveries.canConvertToInt()
                || deliveries.intValue() < 1 || deliveries.intValue() > 3) { throw invalid(); }
        return receipt(service.redeliver(operator, id, key, reason(input), deliveries.intValue()));
    }

    @GetMapping("/operations/{operationId}")
    public Map<String, Object> query(HttpServletRequest request, @PathVariable String operationId) {
        Operator operator = operator(request);
        noBody(request);
        parameters(request, Set.of());
        return service.query(operator, uuid(operationId));
    }

    private JsonNode input(String body, Set<String> fields) {
        try {
            JsonNode input = json.readTree(body);
            if (input == null || !input.isObject() || input.size() != fields.size()
                    || !fields.stream().allMatch(input::has)) { throw invalid(); }
            return input;
        } catch (ApiFailure invalid) { throw invalid; }
        catch (Exception invalid) { throw invalid(); }
    }

    private static String reason(JsonNode input) {
        JsonNode field = input.get("reason");
        if (!field.isTextual()) { throw invalid(); }
        String raw = field.textValue();
        if (raw.codePoints().anyMatch(Character::isISOControl)) { throw invalid(); }
        String reason = raw.trim();
        int length = reason.codePointCount(0, reason.length());
        if (length < 1 || length > 200) { throw invalid(); }
        return reason;
    }

    private static String idempotencyKey(HttpServletRequest request) {
        var headers = Collections.list(request.getHeaders("Idempotency-Key"));
        if (headers.size() != 1 || !headers.get(0).matches("[A-Za-z0-9._:-]{8,64}")) { throw invalid(); }
        if (Collections.list(request.getHeaders("Content-Type")).size() != 1) { throw invalid(); }
        return headers.get(0);
    }

    private static void parameters(HttpServletRequest request, Set<String> allowed) {
        for (var parameter : request.getParameterMap().entrySet()) {
            if (!allowed.contains(parameter.getKey()) || parameter.getValue().length != 1) { throw invalid(); }
        }
    }

    private static void noBody(HttpServletRequest request) {
        try {
            if (request.getContentLengthLong() > 0 || request.getInputStream().read() != -1) { throw invalid(); }
        } catch (IOException unreadable) { throw invalid(); }
    }

    private static String uuid(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw invalid();
        }
        return value;
    }

    private static Operator operator(HttpServletRequest request) {
        if (!(request.getAttribute(PaymentManagementFilter.OPERATOR_ATTRIBUTE) instanceof Operator operator)) {
            throw new ApiFailure(401, "UNAUTHENTICATED");
        }
        return operator;
    }

    private static ResponseEntity<Map<String, Object>> receipt(Map<String, Object> receipt) {
        Object state = receipt.get("status");
        int status = "ACCEPTED".equals(state) ? 202 : "COMPLETED".equals(state) ? 200
                : "REJECTED".equals(state) ? 409 : 0;
        if (status == 0) { throw new ApiFailure(503, "DEPENDENCY_UNAVAILABLE"); }
        return ResponseEntity.status(status).body(receipt);
    }

    private static ApiFailure invalid() { return new ApiFailure(400, "INVALID_ARGUMENT"); }
}
