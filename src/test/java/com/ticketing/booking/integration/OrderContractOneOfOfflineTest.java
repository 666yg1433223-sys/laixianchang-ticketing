package com.ticketing.booking.integration;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ticketing.booking.config.Bootstrap;
import java.nio.file.Files;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Schema-only regression: no application, fixture, database or Redis is started. */
class OrderContractOneOfOfflineTest {
    private final OrderContractTest validator = new OrderContractTest();

    @BeforeEach void loadStaticContract() throws Exception {
        validator.spec = validator.json.readTree(Files.readString(Path.of(System.getProperty("user.dir")).resolve("docs/biz04-openapi.json")));
    }

    private JsonNode conflictSchema(String route) {
        var schema = validator.spec.path("paths").path(route).path("post").path("responses").path("409")
                .path("content").path("application/json").path("schema");
        assertTrue(schema.has("oneOf"), "Test must exercise the checked-in response union");
        return schema;
    }

    private ObjectNode error() throws Exception {
        return (ObjectNode) validator.json.readTree("""
            {"code":"IDEMPOTENCY_CONFLICT","message":"conflict","traceId":"offline-trace"}
            """);
    }

    private ObjectNode receipt() throws Exception {
        return (ObjectNode) validator.json.readTree("""
            {
              "operationId":"12345678-abcd-1234-abcd-1234567890ab",
              "statusUrl":"/v1/internal/sim/operations/12345678-abcd-1234-abcd-1234567890ab",
              "operatorId":"41","action":"RECOVER","attemptId":"17","targetId":"17",
              "reason":"offline regression","deliveries":1,"beforeSettlement":"APPLIED",
              "beforeEvent":"DONE","beforeAttempts":0,"beforeError":null,"generation":"0",
              "status":"REJECTED","acceptedAt":null,
              "completedAt":"2026-09-13T01:02:03.000Z","result":null,
              "rejectionReason":"NOT_RECOVERABLE","simulated":true
            }
            """);
    }

    @ParameterizedTest
    @ValueSource(strings={"/v1/internal/sim/payment-attempts/{attemptId}/recover",
            "/v1/internal/sim/payment-results/{resultId}/redeliver"})
    void actualUnionAcceptsValidError(String route) throws Exception {
        var value=error();
        assertDoesNotThrow(() -> validator.validate(conflictSchema(route),value));
    }

    @ParameterizedTest
    @ValueSource(strings={"/v1/internal/sim/payment-attempts/{attemptId}/recover",
            "/v1/internal/sim/payment-results/{resultId}/redeliver"})
    void actualUnionAcceptsValidOperationReceipt(String route) throws Exception {
        var value=receipt();
        assertDoesNotThrow(() -> validator.validate(conflictSchema(route),value));
    }

    @Test void rejectsZeroMatchingAlternatives() {
        var schema=conflictSchema("/v1/internal/sim/payment-attempts/{attemptId}/recover");
        assertThrows(AssertionError.class,() -> validator.validate(schema,validator.json.createObjectNode()));
    }

    @Test void rejectsReceiptThatViolatesNestedConst() throws Exception {
        var value=receipt().put("simulated",false);
        var schema=conflictSchema("/v1/internal/sim/payment-attempts/{attemptId}/recover");
        assertThrows(AssertionError.class,() -> validator.validate(schema,value));
    }

    @Test void rejectsMixedObjectsInsteadOfMergingAlternatives() throws Exception {
        var value=receipt(); value.setAll(error());
        var schema=conflictSchema("/v1/internal/sim/payment-attempts/{attemptId}/recover");
        assertThrows(AssertionError.class,() -> validator.validate(schema,value));
    }

    @Test void rejectsMultipleMatchingReferencedAlternatives() throws Exception {
        var schema=validator.json.readTree("""
            {"oneOf":[{"$ref":"#/components/schemas/Error"},{"$ref":"#/components/schemas/Error"}]}
            """);
        var value=error();
        assertThrows(AssertionError.class,() -> validator.validate(schema,value));
    }

    @Test void rejectsOverlappingDistinctAlternatives() throws Exception {
        var schema=validator.json.readTree("""
            {"oneOf":[{"type":"string"},{"type":"string","pattern":"^a.*$"}]}
            """);
        assertThrows(AssertionError.class,() -> validator.validate(schema,validator.json.getNodeFactory().textNode("abc")));
        assertDoesNotThrow(() -> validator.validate(schema,validator.json.getNodeFactory().textNode("xyz")));
    }

    @Test void validatesSiblingConstraintsAfterOneOf() throws Exception {
        var schema=validator.json.readTree("""
            {"oneOf":[{"type":"string"},{"type":"integer"}],"type":"string","minLength":3}
            """);
        assertThrows(AssertionError.class,() -> validator.validate(schema,validator.json.getNodeFactory().textNode("a")));
        assertThrows(AssertionError.class,() -> validator.validate(schema,validator.json.getNodeFactory().numberNode(12)));
        assertDoesNotThrow(() -> validator.validate(schema,validator.json.getNodeFactory().textNode("abc")));
    }
}
