package com.ticketing.booking.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.booking.config.Bootstrap;
import com.ticketing.booking.fixture.BookingHarness;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import static org.junit.jupiter.api.Assertions.*;

/** Validates the deliberately limited schema vocabulary used by the checked-in static contract. */
class OrderContractTest {
    final ObjectMapper json = new ObjectMapper();
    JsonNode spec;
    void validate(JsonNode schema, JsonNode value) {
        assertTrue(schema.isObject(), "Missing or unsupported schema " + schema);
        if (schema.has("$ref")) {
            String target = schema.get("$ref").asText();
            assertTrue(target.startsWith("#/components/schemas/"));
            validate(spec.at(target.substring(1)), value); return;
        }
        if (schema.has("oneOf")) {
            var options = schema.get("oneOf");
            assertTrue(options.isArray() && !options.isEmpty(), "oneOf must have schema alternatives");
            int matches = 0;
            for (var option : options) {
                try { validate(option, value); matches++; } catch (AssertionError ignored) { }
            }
            assertEquals(1, matches, "oneOf requires exactly one matching schema for " + value);
        }
        if (schema.has("anyOf")) {
            int matches = 0;
            for (var option : schema.get("anyOf")) {
                try { validate(option, value); matches++; } catch (AssertionError ignored) { }
            }
            assertTrue(matches > 0, "No allowed schema matches " + value);
        }
        var types = new HashSet<String>();
        if (schema.path("type").isArray()) { schema.get("type").forEach(t -> types.add(t.asText())); }
        else { types.add(schema.path("type").asText()); }
        String actual = value.isNull() ? "null" : value.isObject() ? "object" : value.isTextual() ? "string"
                : value.isArray() ? "array" : value.isIntegralNumber() ? "integer" : value.isBoolean() ? "boolean" : "unsupported";
        // Composition schemas need not repeat a type; declared sibling constraints still apply.
        if (schema.has("type")) assertTrue(types.contains(actual), "Type mismatch " + schema + " / " + value);
        if(schema.has("const")) assertEquals(schema.get("const"),value);
        if(value.isArray()) {
            if(schema.has("items")) for(var item:value) validate(schema.get("items"),item);
            if(schema.has("maxItems")) assertTrue(value.size()<=schema.path("maxItems").asInt());
            if(schema.has("minItems")) assertTrue(value.size()>=schema.path("minItems").asInt());
        }
        if (schema.has("enum")) {
            boolean found = false; for (var choice : schema.get("enum")) { if (choice.equals(value)) { found = true; } }
            assertTrue(found, "Enum mismatch " + value);
        }
        if (value.isObject()) {
            for (var key : schema.path("required")) { assertTrue(value.has(key.asText()), "Missing " + key); }
            if (!schema.path("additionalProperties").asBoolean(true)) {
                value.fieldNames().forEachRemaining(key -> assertTrue(schema.path("properties").has(key), "Undocumented " + key));
            }
            value.fields().forEachRemaining(field -> {
                var property = schema.path("properties").path(field.getKey());
                if (!property.isMissingNode()) validate(property, field.getValue());
                else if (schema.path("additionalProperties").isObject()) validate(schema.get("additionalProperties"), field.getValue());
            });
        }
        if (value.isTextual()) {
            int length=value.asText().codePointCount(0,value.asText().length());
            if(schema.has("minLength")) assertTrue(length>=schema.path("minLength").asInt());
            if(schema.has("maxLength")) assertTrue(length<=schema.path("maxLength").asInt());
            if (schema.has("pattern")) { assertTrue(value.asText().matches(schema.get("pattern").asText())); }
            if ("date-time".equals(schema.path("format").asText())) { assertDoesNotThrow(() -> Instant.parse(value.asText())); }
            if (schema.path("description").asText().contains("signed BIGINT")) { assertDoesNotThrow(() -> Long.parseLong(value.asText())); }
        }
        if (value.isIntegralNumber()) {
            if (schema.has("minimum")) { assertTrue(value.longValue() >= schema.get("minimum").longValue()); }
            if (schema.has("maximum")) { assertTrue(value.longValue() <= schema.get("maximum").longValue()); }
        }
    }
    void response(String route, String method, BookingHarness.Reply result) {
        var schema = spec.path("paths").path(route).path(method).path("responses").path(Integer.toString(result.status()))
                .path("content").path("application/json").path("schema");
        assertFalse(schema.isMissingNode(), "Undocumented HTTP response"); validate(schema, result.body());
    }

    @Test void staticContractMatchesActualRoutesBodiesAndDatabaseChecks() throws Exception {
        spec = json.readTree(Files.readString(Bootstrap.ROOT.resolve("docs/biz04-openapi.json")));
        assertEquals("3.1.0", spec.path("openapi").asText());
        try (var h = new BookingHarness(System.getProperty("biz.runId") + "-contract")) {
            Set<String> expected = new TreeSet<>();
            spec.path("paths").fields().forEachRemaining(p -> p.getValue().fieldNames()
                    .forEachRemaining(method -> expected.add(method.toUpperCase(Locale.ROOT) + " " + p.getKey())));
            Set<String> actual = new TreeSet<>();
            var mappings = h.app.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
            mappings.getHandlerMethods().forEach((mapping, handler) -> {
                for (String pattern : mapping.getPatternValues()) {
                    if (pattern.startsWith("/v1/")) {
                        mapping.getMethodsCondition().getMethods().forEach(method -> actual.add(method.name() + " " + pattern));
                    }
                }
            });
            assertEquals(expected, actual); assertEquals(10, actual.size());
            var f = h.fixture("API", 1);
            var accepted = h.post(f, "api-contract-key");
            response("/v1/booking-requests", "post", accepted);
            var request = h.query(accepted.id()); response("/v1/booking-requests/{id}", "get", request);
            long order = Long.parseLong(request.body().path("order").path("orderId").asText());
            String route = "/v1/orders/" + order;
            response("/v1/orders/{id}", "get", h.request("GET", route, null, null, false, true));
            assertThrows(Exception.class, () -> h.jdbc.update("UPDATE booking_order SET closed_at=hold_created_at WHERE id=?", order));
            assertThrows(Exception.class, () -> h.jdbc.update("UPDATE booking_order SET status='CANCELLED',closed_at=pay_expires_at,close_reason='USER_CANCELLED' WHERE id=?", order));
            assertThrows(Exception.class, () -> h.jdbc.update("UPDATE booking_order SET status='EXPIRED',closed_at=hold_created_at,close_reason='PAYMENT_DEADLINE_EXCEEDED' WHERE id=?", order));
            assertThrows(Exception.class, () -> h.jdbc.update("UPDATE booking_order SET status='CANCELLED',closed_at=hold_created_at,close_reason=NULL WHERE id=?", order));
            assertThrows(Exception.class, () -> h.jdbc.update("INSERT INTO inventory_ledger(tier_id,order_id,action,delta,available_after,created_at) VALUES(?,?,'RELEASE',-1,1,UTC_TIMESTAMP(3))", f.tier(), order));
            h.assertCounts(f, 1, 1, 1, 0);
            var queryField = h.request("POST", route + "/cancel?userId=1", "{}", null, false, true);
            assertEquals(400, queryField.status()); response("/v1/orders/{id}/cancel", "post", queryField);
            var media = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:18083" + route + "/cancel"))
                .timeout(java.time.Duration.ofSeconds(5)).header("Authorization", "Bearer " + h.runtime.token(false))
                .header("Content-Type", "text/plain").POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}")).build();
            var badMedia = h.http.send(media, java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(400, badMedia.statusCode());
            h.log(Map.of("type", "HTTP", "method", "POST", "path", route + "/cancel", "status", badMedia.statusCode(), "body", json.readTree(badMedia.body())));
            for (boolean auth : List.of(false, true)) {
                var hidden = h.request("POST", route + "/cancel", "{}", null, true, auth);
                assertEquals(auth ? 404 : 401, hidden.status()); response("/v1/orders/{id}/cancel", "post", hidden);
            }
            h.runtime.hooks.put("CLOSE_AFTER_STATUS", id -> { throw new IllegalStateException("CONTRACT_TEMPORARY_FAILURE"); });
            var failed = h.request("POST", route + "/cancel", "{}", null, false, true);
            assertEquals(503, failed.status()); response("/v1/orders/{id}/cancel", "post", failed);
            h.runtime.hooks.clear();
            var cancelled = h.request("POST", route + "/cancel", "{}", null, false, true);
            assertEquals(200, cancelled.status()); response("/v1/orders/{id}/cancel", "post", cancelled);
            response("/v1/booking-requests/{id}", "get", h.query(accepted.id()));
            assertEquals(cancelled.body(), h.request("POST", route + "/cancel", "{}", null, false, true).body());
            h.assertCounts(f, 1, 1, 2, 1);
            var expiredFixture = h.fixture("API-expired", 1);
            var exp = h.post(expiredFixture, "api-expired-key");
            long expId = Long.parseLong(h.query(exp.id()).body().path("order").path("orderId").asText());
            h.runtime.fixedTime = BookingHarness.BASE.plusSeconds(600);
            var expired = h.request("POST", "/v1/orders/" + expId + "/cancel", "{}", null, false, true);
            assertEquals("EXPIRED", expired.body().path("orderStatus").asText());
            response("/v1/orders/{id}/cancel", "post", expired);
            response("/v1/orders/{id}", "get", h.request("GET", "/v1/orders/" + expId, null, null, false, true));
            h.assertCounts(expiredFixture, 1, 1, 2, 1);
            h.save("static-contract-check.json", Map.of("routes", actual, "schemaVocabulary", "ref/oneOf/anyOf/type/required/additionalProperties/enum/pattern/format/min/max",
                "httpStates", List.of("HOLD", "CANCELLED", "EXPIRED"), "sqlInvalidCombinationsRejected", 5, "onlineDocsEndpoint", false));
            h.pass(f, "Static contract vs real HTTP and SQL negative checks"); h.pass(expiredFixture, "EXPIRED response matches static schema");
            h.cleanOnClose = true;
        }
    }
}
