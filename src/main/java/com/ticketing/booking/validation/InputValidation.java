package com.ticketing.booking.validation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.booking.domain.ApiFailure;
import java.util.HashSet;
import java.util.Set;

public final class InputValidation {
    private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private InputValidation() { }
    public static JsonNode body(String value,Set<String> fields) {
        try {
            JsonNode node=JSON.readTree(value);var actual=new HashSet<String>();
            node.fieldNames().forEachRemaining(actual::add);
            if (!node.isObject() || !actual.equals(fields)) { throw new ApiFailure(400,"INVALID_ARGUMENT"); }
            return node;
        } catch (Exception error) { throw new ApiFailure(400,"INVALID_ARGUMENT"); }
    }
    public static String key(String key) {
        if (key==null || !key.matches("[A-Za-z0-9._:-]{8,64}")) { throw new ApiFailure(400,"INVALID_ARGUMENT"); }
        return key;
    }
    public static int page(int page,int size) {
        if(page<1 || page>10000 || size<1 || size>50) { throw new ApiFailure(400,"INVALID_ARGUMENT"); }
        return (page-1)*size;
    }
}
