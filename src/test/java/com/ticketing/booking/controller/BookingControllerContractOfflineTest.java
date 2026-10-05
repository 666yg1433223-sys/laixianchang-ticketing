package com.ticketing.booking.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.booking.config.Diagnostics;
import com.ticketing.booking.exception.ApiErrors;
import com.ticketing.booking.model.dto.CreateBookingDTO;
import com.ticketing.booking.model.vo.BookingAcceptedVO;
import com.ticketing.booking.model.vo.BookingStatusVO;
import com.ticketing.booking.service.BookingService;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP contracts that must survive moving the implementation behind typed service interfaces. */
class BookingControllerContractOfflineTest {
    private static final String ROUTE = "/v1/booking-requests";
    private static final long USER = 52L;
    private static final long SESSION = 9007199254740993L;
    private static final long TIER = 9007199254740995L;
    private static final String REQUEST = "9007199254740997";
    private static final String KEY = "offline:booking-001";
    private static final String ACCEPTED_AT = "2030-06-01T10:00:00.000Z";
    private static final String DEADLINE = "2030-06-01T10:00:30.000Z";
    private static final String VALID_BODY = "{\"sessionId\":\"9007199254740993\",\"tierId\":\"9007199254740995\",\"quantity\":1}";
    private BookingService service;
    private Diagnostics diagnostics;
    private MockMvc mvc;

    @BeforeEach void setup() {
        service = mock(BookingService.class);
        diagnostics = mock(Diagnostics.class);
        // Use Spring MVC's real message converters, matching the existing HTTP contract tests.
        mvc = MockMvcBuilders.standaloneSetup(new BookingController(service))
            .setControllerAdvice(new ApiErrors(diagnostics)).build();
    }

    private static MockHttpServletRequestBuilder booking(String body) {
        return post(ROUTE).requestAttr("booking.principal", USER)
            .header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private void invalid(String body) throws Exception {
        mvc.perform(booking(body)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        verifyNoInteractions(service, diagnostics);
    }

    @Test void acceptedResponseHas202LocationAndTheOriginalStringIdJsonContract() throws Exception {
        var command = new CreateBookingDTO(USER, SESSION, TIER, KEY);
        var receipt = new BookingAcceptedVO(REQUEST, ROUTE + "/" + REQUEST, ACCEPTED_AT, DEADLINE);
        when(service.accept(command)).thenReturn(receipt);

        var response = mvc.perform(booking(VALID_BODY))
            .andExpect(status().isAccepted())
            .andExpect(header().string("Location", ROUTE + "/" + REQUEST))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.requestId").isString())
            .andExpect(jsonPath("$.requestId").value(REQUEST))
            .andReturn().getResponse().getContentAsString();

        ObjectMapper json = new ObjectMapper();
        assertEquals(json.readTree("""
            {"requestId":"9007199254740997","statusUrl":"/v1/booking-requests/9007199254740997",
             "acceptedAt":"2030-06-01T10:00:00.000Z","processDeadline":"2030-06-01T10:00:30.000Z"}
            """), json.readTree(response));
        verify(service).accept(command);
        verifyNoMoreInteractions(service);
        verifyNoInteractions(diagnostics);
    }

    @Test void statusResponseRetainsNullFieldsAndPassesOnlyTheServerPrincipalToService() throws Exception {
        when(service.query(USER, Long.parseLong(REQUEST))).thenReturn(new BookingStatusVO(
            REQUEST, "ACCEPTED", null, ACCEPTED_AT, DEADLINE, "2030-06-01T10:00:02.000Z", null));

        String response = mvc.perform(get(ROUTE + "/" + REQUEST).requestAttr("booking.principal", USER)
                .header("userId", "999").header("role", "ADMIN"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.requestId").isString())
            .andExpect(jsonPath("$.status").value("ACCEPTED"))
            .andReturn().getResponse().getContentAsString();

        ObjectMapper json = new ObjectMapper();
        assertEquals(json.readTree("""
            {"requestId":"9007199254740997","status":"ACCEPTED","reasonCode":null,
             "acceptedAt":"2030-06-01T10:00:00.000Z","processDeadline":"2030-06-01T10:00:30.000Z",
             "serverTime":"2030-06-01T10:00:02.000Z","order":null}
            """), json.readTree(response));
        verify(service).query(USER, Long.parseLong(REQUEST));
        verifyNoMoreInteractions(service);
    }

    static Stream<String> malformedBodies() {
        return Stream.of(
            "null", "[]", "{}", "{", "true", "\"booking\"",
            "{\"sessionId\":\"1\",\"tierId\":\"2\"}",
            "{\"sessionId\":\"1\",\"tierId\":\"2\",\"quantity\":1,\"userId\":\"999\"}",
            "{\"sessionId\":\"1\",\"sessionId\":\"1\",\"tierId\":\"2\",\"quantity\":1}",
            "{\"sessionId\":\"1\",\"tierId\":\"2\",\"tierId\":\"3\",\"quantity\":1}",
            "{\"sessionId\":\"1\",\"tierId\":\"2\",\"quantity\":1,\"quantity\":1}",
            VALID_BODY + " {}", VALID_BODY + " null", VALID_BODY + " []",
            "{\"sessionId\":1,\"tierId\":\"2\",\"quantity\":1}",
            "{\"sessionId\":\"1\",\"tierId\":2,\"quantity\":1}",
            "{\"sessionId\":null,\"tierId\":\"2\",\"quantity\":1}",
            "{\"sessionId\":\"1\",\"tierId\":null,\"quantity\":1}");
    }

    @ParameterizedTest @MethodSource("malformedBodies")
    void malformedOrAmbiguousJsonNeverReachesService(String body) throws Exception { invalid(body); }

    @ParameterizedTest
    @ValueSource(strings = {"0", "2", "-1", "1.0", "1.00", "1e0", "\"1\"", "null", "true", "[]", "{}", "2147483648", "999999999999999999999"})
    void quantityMustBeTheIntegerOneWithoutCoercion(String quantity) throws Exception {
        invalid("{\"sessionId\":\"1\",\"tierId\":\"2\",\"quantity\":" + quantity + "}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "01", "-1", "+1", "1.0", "1 ", " 1", "", "9223372036854775808"})
    void textualIdsMustStillBeCanonicalPositiveSignedBigints(String id) throws Exception {
        invalid("{\"sessionId\":\"" + id + "\",\"tierId\":\"2\",\"quantity\":1}");
        invalid("{\"sessionId\":\"1\",\"tierId\":\"" + id + "\",\"quantity\":1}");
    }
}
