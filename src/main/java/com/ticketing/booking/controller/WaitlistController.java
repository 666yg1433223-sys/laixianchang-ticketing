package com.ticketing.booking.controller;

import com.ticketing.booking.model.dto.*;
import com.ticketing.booking.service.WaitlistService;
import com.ticketing.booking.model.vo.*;
import com.ticketing.booking.validation.InputValidation;

import com.ticketing.booking.domain.ApiFailure;
import com.ticketing.booking.domain.Values;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("local-sim")
@RequestMapping("/v1/waitlist-entries")
public class WaitlistController {
    private final WaitlistService service;
    public WaitlistController(WaitlistService service) { this.service=service; }
    @PostMapping(consumes="application/json")
    public WaitlistVO join(HttpServletRequest request,@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody String body) {
        var n=InputValidation.body(body,Set.of("sessionId","tierId","quantity"));
        if(!n.path("sessionId").isTextual() || !n.path("tierId").isTextual() || !n.path("quantity").isIntegralNumber() || !n.path("quantity").canConvertToInt() || n.path("quantity").intValue()!=1) { throw new ApiFailure(400,"INVALID_ARGUMENT"); }
        return service.join(new JoinWaitlistDTO(actor(request),Values.id(n.path("sessionId").asText()),Values.id(n.path("tierId").asText()),key));
    }
    @GetMapping public PageVO<WaitlistVO> list(HttpServletRequest request,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) { return service.list(actor(request),page,size); }
    @GetMapping("/{id}") public WaitlistVO query(HttpServletRequest request,@PathVariable String id) { return service.query(actor(request),Values.id(id)); }
    @PostMapping(value="/{id}/cancel",consumes="application/json") public WaitlistVO cancel(HttpServletRequest request,@PathVariable String id,@RequestBody String body) { InputValidation.body(body,Set.of());return service.cancel(actor(request),Values.id(id)); }
    private static long actor(HttpServletRequest r) { return (Long)r.getAttribute("booking.principal"); }
}
