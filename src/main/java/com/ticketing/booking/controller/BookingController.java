package com.ticketing.booking.controller;

import com.ticketing.booking.model.dto.*;
import com.ticketing.booking.model.vo.*;
import com.ticketing.booking.validation.InputValidation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.booking.domain.ApiFailure;
import com.ticketing.booking.domain.Values;
import com.ticketing.booking.service.BookingService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/booking-requests")
public class BookingController {
    private final BookingService service;
    private final ObjectMapper json=new ObjectMapper()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    public BookingController(BookingService service) { this.service=service; }

    @PostMapping(consumes="application/json",produces="application/json")
    public ResponseEntity<BookingAcceptedVO> accept(HttpServletRequest request,
            @RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody String body) {
        long session,tier;
        try {
            var input=json.readTree(body);
            Set<String> names=new HashSet<>(); input.fieldNames().forEachRemaining(names::add);
            if (!input.isObject() || !names.equals(Set.of("sessionId","tierId","quantity"))
                    || !input.path("sessionId").isTextual() || !input.path("tierId").isTextual()
                    || !input.path("quantity").isIntegralNumber() || !input.path("quantity").canConvertToInt()
                    || input.path("quantity").intValue()!=1) { throw new ApiFailure(400,"INVALID_ARGUMENT"); }
            session=Values.id(input.path("sessionId").asText()); tier=Values.id(input.path("tierId").asText());
        } catch (ApiFailure invalid) { throw invalid; }
        catch (Exception invalid) { throw new ApiFailure(400,"INVALID_ARGUMENT"); }
        var accepted=service.accept(new CreateBookingDTO((Long)request.getAttribute("booking.principal"),session,tier,key));
        return ResponseEntity.accepted().location(URI.create(accepted.statusUrl())).body(accepted);
    }

    @GetMapping(value="/{id}",produces="application/json")
    public BookingStatusVO query(HttpServletRequest request,@PathVariable String id) {
        return service.query((Long)request.getAttribute("booking.principal"),Values.id(id));
    }
}
