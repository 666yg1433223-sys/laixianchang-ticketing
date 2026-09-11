package com.ticketing.booking.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.ticketing.booking.domain.*;
import com.ticketing.booking.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class PaymentController {
    private final PaymentService service;
    private final ObjectMapper json=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    public PaymentController(PaymentService service) { this.service=service; }
    @PostMapping(value="/v1/orders/{id}/payment-attempts",consumes="application/json",produces="application/json")
    public ResponseEntity<Map<String,Object>> accept(HttpServletRequest request,@PathVariable String id,
            @RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody String body) {
        long order=Values.id(id);
        if(!request.getParameterMap().isEmpty()) { throw new ApiFailure(400,"INVALID_ARGUMENT"); }
        try { var input=json.readTree(body); if(input==null || !input.isObject() || !input.isEmpty()) { throw new IllegalArgumentException(); } }
        catch(Exception invalid) { throw new ApiFailure(400,"INVALID_ARGUMENT"); }
        return ResponseEntity.accepted().body(service.accept((Long)request.getAttribute("booking.principal"),order,key));
    }
    @GetMapping(value="/v1/payment-attempts/{id}",produces="application/json")
    public Map<String,Object> query(HttpServletRequest request,@PathVariable String id) {
        if(!request.getParameterMap().isEmpty()) { throw new ApiFailure(400,"INVALID_ARGUMENT"); }
        return service.query((Long)request.getAttribute("booking.principal"),Values.id(id));
    }
}
