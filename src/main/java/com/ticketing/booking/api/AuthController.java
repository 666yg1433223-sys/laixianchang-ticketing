package com.ticketing.booking.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.booking.auth.AuthService;
import com.ticketing.booking.domain.ApiFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("local-sim")
@RequestMapping("/v1/auth")
public class AuthController {
    private final AuthService service;
    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    public AuthController(AuthService service) { this.service = service; }

    @PostMapping(value = "/code", consumes = "application/json", produces = "application/json")
    public Map<String,Object> code(@RequestBody String body, HttpServletRequest request) {
        var input = read(body, Set.of("phone"));
        // The TCP peer is used, never an untrusted X-Forwarded-For value on this loopback server.
        return service.sendCode(input.get("phone"), request.getRemoteAddr());
    }
    @PostMapping(value = "/login", consumes = "application/json", produces = "application/json")
    public Map<String,Object> login(@RequestBody String body) {
        var input = read(body, Set.of("phone", "code"));
        return service.login(input.get("phone"), input.get("code"));
    }
    @GetMapping(value = "/me", produces = "application/json")
    public Map<String,Object> me(HttpServletRequest request) {
        return service.me((Long) request.getAttribute("booking.principal"));
    }
    @PostMapping(value = "/logout", produces = "application/json")
    public Map<String,Object> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.logout(authorization);
    }

    private Map<String,String> read(String body, Set<String> fields) {
        try {
            if (body.length() > 1024) { throw new ApiFailure(400, "INVALID_ARGUMENT"); }
            var input = json.readTree(body);
            var names = new HashSet<String>(); input.fieldNames().forEachRemaining(names::add);
            if (!input.isObject() || !fields.equals(names)) { throw new ApiFailure(400, "INVALID_ARGUMENT"); }
            var result = new java.util.HashMap<String,String>();
            for (String field : fields) {
                if (!input.path(field).isTextual()) { throw new ApiFailure(400, "INVALID_ARGUMENT"); }
                result.put(field, input.path(field).asText());
            }
            return result;
        } catch (ApiFailure failure) { throw failure; }
        catch (Exception failure) { throw new ApiFailure(400, "INVALID_ARGUMENT"); }
    }
}
