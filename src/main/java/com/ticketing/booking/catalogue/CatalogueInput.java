package com.ticketing.booking.catalogue;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.booking.domain.ApiFailure;
import com.ticketing.booking.domain.Values;
import com.ticketing.booking.model.dto.CatalogueEventDTO;
import com.ticketing.booking.model.dto.CatalogueSessionDTO;
import com.ticketing.booking.model.dto.CatalogueTierDTO;
import java.time.Instant;
import java.util.Set;

/** Explicit allow-lists prevent trusted fields, duplicate JSON and accidental partial overwrites. */
public final class CatalogueInput {
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private CatalogueInput() { }
    public static JsonNode body(String raw, Set<String> allowed, Set<String> required) {
        try {
            var node=JSON.readTree(raw);
            if(node==null||!node.isObject()) { throw invalid(); }
            var names=node.fieldNames();
            while(names.hasNext()) { if(!allowed.contains(names.next())) { throw invalid(); } }
            for(String key:required) { if(!node.has(key)||node.get(key).isNull()) { throw invalid(); } }
            return node;
        } catch(ApiFailure e) { throw e; }
        catch(Exception e) { throw invalid(); }
    }
    public static String text(JsonNode node,String key,int max,boolean blankAllowed) {
        var value=node.get(key);
        if(value==null||!value.isTextual()) { throw invalid(); }
        String result=value.textValue().trim();
        if(result.length()>max||(!blankAllowed&&result.isEmpty())||result.indexOf('\u0000')>=0) { throw invalid(); }
        return result;
    }
    public static long id(JsonNode node,String key) {
        if(node.get(key)==null||!node.get(key).isTextual()) { throw invalid(); }
        return Values.id(node.get(key).textValue());
    }
    public static int integer(JsonNode node,String key,int min,int max) {
        var value=node.get(key);
        if(value==null||!value.isIntegralNumber()||!value.canConvertToInt()) { throw invalid(); }
        int result=value.intValue(); if(result<min||result>max) { throw invalid(); } return result;
    }
    public static Instant instant(JsonNode node,String key) {
        try {
            String value=text(node,key,40,false);
            if(!value.endsWith("Z")) { throw invalid(); }
            Instant result=Instant.parse(value);
            if(result.getNano()%1_000_000!=0) { throw invalid(); }
            return result;
        } catch(ApiFailure e) { throw e; } catch(Exception e) { throw invalid(); }
    }
    public static int page(String value,int fallback,int maximum) {
        if(value==null) { return fallback; }
        if(!value.matches("[1-9][0-9]{0,4}")) { throw invalid(); }
        int number=Integer.parseInt(value); if(number>maximum) { throw invalid(); } return number;
    }
    public static CatalogueEventDTO eventFields(JsonNode input, boolean creating) {
        return new CatalogueEventDTO(
            input.has("title") ? text(input,"title",128,false) : null,
            input.has("description") ? text(input,"description",5000,true) : creating ? "" : null,
            input.has("venueText") ? text(input,"venueText",255,true) : creating ? "" : null);
    }
    public static CatalogueSessionDTO sessionFields(JsonNode input) {
        return new CatalogueSessionDTO(
            input.has("eventId") ? id(input,"eventId") : null,
            input.has("startAt") ? instant(input,"startAt") : null,
            input.has("bookingOpenAt") ? instant(input,"bookingOpenAt") : null,
            input.has("bookingCloseAt") ? instant(input,"bookingCloseAt") : null);
    }
    public static CatalogueTierDTO tierFields(JsonNode input, boolean creating) {
        return new CatalogueTierDTO(
            input.has("sessionId") ? id(input,"sessionId") : null,
            input.has("name") ? text(input,"name",64,false) : null,
            input.has("areaLabel") ? text(input,"areaLabel",64,true) : creating ? "" : null,
            input.has("capacity") ? integer(input,"capacity",1,1_000_000) : null,
            input.has("unitPriceCent") ? integer(input,"unitPriceCent",1,1_000_000) : null);
    }
    public static void window(Instant open,Instant close,Instant start,Instant now) {
        if(!open.isBefore(close)||!close.isBefore(start)||!now.isBefore(close)) { throw new ApiFailure(400,"INVALID_BOOKING_WINDOW"); }
    }
    public static ApiFailure invalid() { return new ApiFailure(400,"INVALID_ARGUMENT"); }
}
