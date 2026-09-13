package com.ticketing.booking.product;

import com.ticketing.booking.auth.AuthService;
import com.ticketing.booking.domain.ApiFailure;
import com.ticketing.booking.service.RefundService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.ticketing.booking.domain.Values.id;

@RestController
@Profile("local-sim")
public class RefundController {
    private final RefundService service;
    private final AuthService auth;
    public RefundController(RefundService service,AuthService auth) { this.service=service;this.auth=auth; }

    @PostMapping(value="/v1/orders/{orderId}/refunds",consumes="application/json")
    public ResponseEntity<Map<String,Object>> accept(HttpServletRequest request,@PathVariable String orderId,
            @RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody String body) {
        return ResponseEntity.accepted().body(service.accept(actor(request),id(orderId),key,reason(body)));
    }
    @GetMapping("/v1/refunds/{refundId}")
    public Map<String,Object> query(HttpServletRequest request,@PathVariable String refundId) {
        return service.query(actor(request),id(refundId));
    }
    @GetMapping("/v1/admin/refunds")
    public Map<String,Object> list(HttpServletRequest request,@RequestParam(defaultValue="REVIEW") String status,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        auth.requireAdmin(actor(request));return service.listForAdmin(status,page,size);
    }
    @GetMapping("/v1/admin/refunds/{refundId}")
    public Map<String,Object> adminQuery(HttpServletRequest request,@PathVariable String refundId) {
        auth.requireAdmin(actor(request));return adminReceipt(service.queryForAdmin(id(refundId)));
    }
    @PostMapping(value="/v1/admin/refunds/{refundId}/retry",consumes="application/json")
    public ResponseEntity<Map<String,Object>> retry(HttpServletRequest request,@PathVariable String refundId,
            @RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody String body) {
        long actor=actor(request);auth.requireAdmin(actor);
        return ResponseEntity.accepted().body(adminReceipt(service.retry(actor,id(refundId),key,reason(body))));
    }
    private static Map<String,Object> adminReceipt(Map<String,Object> original) {
        var result=new LinkedHashMap<String,Object>(original);
        result.put("statusUrl","/v1/admin/refunds/"+original.get("refundId"));
        return result;
    }
    private static String reason(String body) {
        var node=ProductInput.body(body,Set.of("reason"));
        if(!node.path("reason").isTextual()) { throw new ApiFailure(400,"INVALID_ARGUMENT"); }
        return node.path("reason").asText();
    }
    private static long actor(HttpServletRequest request) {
        Object principal=request.getAttribute("booking.principal");
        if(!(principal instanceof Long user)) { throw new ApiFailure(401,"UNAUTHENTICATED"); }
        return user;
    }
}
