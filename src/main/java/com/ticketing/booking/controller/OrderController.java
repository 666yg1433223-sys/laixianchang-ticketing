package com.ticketing.booking.controller;

import com.ticketing.booking.model.dto.*;
import com.ticketing.booking.model.vo.*;
import com.ticketing.booking.validation.InputValidation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.booking.domain.ApiFailure;
import com.ticketing.booking.domain.Values;
import com.ticketing.booking.service.OrderCloseService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/orders")
public class OrderController {
    private final OrderCloseService service;
    private final ObjectMapper json = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    public OrderController(OrderCloseService service) { this.service = service; }

    @GetMapping(value = "/{id}", produces = "application/json")
    public OrderVO query(HttpServletRequest request, @PathVariable String id) {
        return service.query((Long) request.getAttribute("booking.principal"), Values.id(id));
    }

    @PostMapping(value = "/{id}/cancel", consumes = "application/json", produces = "application/json")
    public OrderVO cancel(HttpServletRequest request, @PathVariable String id, @RequestBody String body) {
        long orderId = Values.id(id);
        if (!request.getParameterMap().isEmpty()) { throw new ApiFailure(400, "INVALID_ARGUMENT"); }
        try {
            var input = json.readTree(body);
            if (input == null || !input.isObject() || !input.isEmpty()) { throw new ApiFailure(400, "INVALID_ARGUMENT"); }
        } catch (ApiFailure invalid) { throw invalid; }
        catch (Exception invalid) { throw new ApiFailure(400, "INVALID_ARGUMENT"); }
        return service.cancel((Long) request.getAttribute("booking.principal"), orderId);
    }
}
