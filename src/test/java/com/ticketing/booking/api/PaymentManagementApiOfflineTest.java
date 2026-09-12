package com.ticketing.booking.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.booking.auth.PaymentManagementAccess;
import com.ticketing.booking.auth.PaymentManagementAccess.Operator;
import com.ticketing.booking.config.Diagnostics;
import com.ticketing.booking.config.RuntimeServices;
import com.ticketing.booking.domain.ApiFailure;
import com.ticketing.booking.service.PaymentManagementService;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PaymentManagementApiOfflineTest {
    private static final String PREFIX = "/v1/internal/sim";
    private static final String OPERATOR_TOKEN = "Bearer offline-operator";
    private static final String KEY = "offline:key-001";
    private static final String RESULT = "12345678-abcd-1234-abcd-1234567890ab";
    private static final String OPERATION = "abcdef01-abcd-1234-abcd-1234567890ab";
    private static final Operator OPERATOR = new Operator(41, Set.of(11L, 12L));
    private RuntimeServices runtime;
    private PaymentManagementService service;
    private Diagnostics diagnostics;
    private MockMvc mvc;

    @BeforeEach void setup() {
        runtime = mock(RuntimeServices.class);
        service = mock(PaymentManagementService.class);
        diagnostics = mock(Diagnostics.class);
        when(runtime.authenticateManagement(OPERATOR_TOKEN)).thenReturn(new RuntimeServices.ManagementPrincipal(41, true, true));
        when(runtime.authenticateManagement("Bearer ordinary")).thenReturn(new RuntimeServices.ManagementPrincipal(17, false, false));
        when(runtime.authenticateManagement("Bearer role-only")).thenReturn(new RuntimeServices.ManagementPrincipal(42, true, false));
        when(runtime.authenticateManagement("Bearer capability-only")).thenReturn(new RuntimeServices.ManagementPrincipal(43, false, true));
        when(runtime.managementReady()).thenReturn(true);
        when(runtime.managementAttempts()).thenReturn(OPERATOR.attemptIds());
        MockEnvironment environment = new MockEnvironment().withProperty("biz.sim.management.enabled", "true");
        environment.setActiveProfiles("biz01-it");
        mvc = MockMvcBuilders.standaloneSetup(new PaymentManagementController(service))
                .setControllerAdvice(new PaymentManagementErrors(diagnostics))
                .addFilters(new PaymentManagementFilter(new PaymentManagementAccess(runtime, environment), diagnostics))
                .build();
    }

    private static MockHttpServletRequestBuilder recover(String body) {
        return post(PREFIX + "/payment-attempts/11/recover").header("Authorization", OPERATOR_TOKEN)
                .header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder redeliver(String body) {
        return post(PREFIX + "/payment-results/" + RESULT + "/redeliver").header("Authorization", OPERATOR_TOKEN)
                .header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private void invalid(MockHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        verifyNoInteractions(service);
    }

    @Test void unauthenticatedRequestsAndDuplicateAuthorizationFailBeforeMalformedArguments() throws Exception {
        for (MockHttpServletRequestBuilder request : List.of(
                post(PREFIX + "/payment-attempts/not-an-id/recover").contentType(MediaType.APPLICATION_JSON).content("{"),
                post(PREFIX + "/payment-attempts/not-an-id/recover").header("Authorization", "Bearer unknown")
                        .contentType(MediaType.APPLICATION_JSON).content("{"),
                recover("{").header("Authorization", OPERATOR_TOKEN),
                get(PREFIX + "/operations/not-a-uuid").param("runId", "injected"))) {
            mvc.perform(request).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
        verifyNoInteractions(service);
        verify(runtime, never()).managementAttempts();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ordinary", "role-only", "capability-only"})
    void ordinaryIdentityAndPartialPermissionsCannotBeElevatedWithHeaders(String token) throws Exception {
        mvc.perform(post(PREFIX + "/payment-attempts/not-an-id/recover").header("Authorization", "Bearer " + token)
                .header("role", "ADMIN").header("userId", "41").header("SIM_TEST_OPERATOR", "true")
                .contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SIM_MANAGEMENT_FORBIDDEN"));
        verifyNoInteractions(service);
        verify(runtime, never()).managementAttempts();
    }

    @Test void disabledManagementRejectsAuthorizedIdentityBeforeBinding() throws Exception {
        when(runtime.managementReady()).thenReturn(false);
        mvc.perform(recover("{"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SIM_MANAGEMENT_FORBIDDEN"));
        verifyNoInteractions(service);
    }

    @Test void listDefaultsAndSignedBigintCursorReachServiceWithOnlyServerScope() throws Exception {
        when(service.list(OPERATOR, 0, 20)).thenReturn(Map.of("items", List.of()));
        when(service.list(OPERATOR, Long.MAX_VALUE, 50)).thenReturn(Map.of("items", List.of()));
        mvc.perform(get(PREFIX + "/payment-failures").header("Authorization", OPERATOR_TOKEN)).andExpect(status().isOk());
        mvc.perform(get(PREFIX + "/payment-failures").header("Authorization", OPERATOR_TOKEN)
                .param("afterAttemptId", Long.toString(Long.MAX_VALUE)).param("limit", "50")).andExpect(status().isOk());
        verify(service).list(OPERATOR, 0, 20);
        verify(service).list(OPERATOR, Long.MAX_VALUE, 50);
        verifyNoMoreInteractions(service);
    }

    static Stream<Arguments> invalidPageParameters() {
        return Stream.of(Arguments.of("afterAttemptId", "00"), Arguments.of("afterAttemptId", "-1"),
                Arguments.of("afterAttemptId", "9223372036854775808"), Arguments.of("afterAttemptId", ""),
                Arguments.of("limit", "0"), Arguments.of("limit", "51"), Arguments.of("limit", "01"),
                Arguments.of("limit", "1.0"), Arguments.of("limit", "+1"), Arguments.of("limit", ""),
                Arguments.of("runId", "other-run"), Arguments.of("attemptIds", "11,12"));
    }

    @ParameterizedTest @MethodSource("invalidPageParameters")
    void rejectsInvalidOrUnknownPageParameters(String name, String value) throws Exception {
        invalid(get(PREFIX + "/payment-failures").header("Authorization", OPERATOR_TOKEN).param(name, value));
    }

    @Test void rejectsDuplicateQueriesBodiesOnGetAndAnyMutationQuery() throws Exception {
        invalid(get(PREFIX + "/payment-failures").header("Authorization", OPERATOR_TOKEN).param("limit", "20", "20"));
        invalid(get(PREFIX + "/payment-failures").header("Authorization", OPERATOR_TOKEN).param("afterAttemptId", "0", "0"));
        invalid(get(PREFIX + "/payment-failures").header("Authorization", OPERATOR_TOKEN).content("{}"));
        invalid(get(PREFIX + "/operations/" + OPERATION).header("Authorization", OPERATOR_TOKEN).content("{}"));
        invalid(get(PREFIX + "/operations/" + OPERATION).header("Authorization", OPERATOR_TOKEN).param("limit", "1"));
        invalid(recover("{\"reason\":\"retry\"}").param("outcome", "SUCCESS"));
        invalid(redeliver("{\"reason\":\"retry\",\"deliveries\":1}").param("runId", "other-run"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "01", "-1", "+1", "1.0", "9223372036854775808"})
    void recoverRejectsNoncanonicalOrOverflowingAttemptIds(String id) throws Exception {
        invalid(post(PREFIX + "/payment-attempts/" + id + "/recover").header("Authorization", OPERATOR_TOKEN)
                .header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"retry\"}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "null", "{", "{\"reason\":null}", "{\"reason\":1}",
            "{\"reason\":\"\"}", "{\"reason\":\"   \"}", "{\"reason\":\"retry\",\"reason\":\"retry\"}",
            "{\"reason\":\"retry\"} {}", "{\"reason\":\"retry\",\"outcome\":\"SUCCESS\"}",
            "{\"reason\":\"retry\",\"deliveries\":1}", "{\"reason\":\"retry\\n\"}", "{\"reason\":\"\\t retry\"}",
            "{\"reason\":\"retry\\u007f\"}", "{\"reason\":\"retry\\u0085\"}"})
    void recoverStrictlyRejectsMalformedJsonAndControlInputs(String body) throws Exception { invalid(recover(body)); }

    @Test void reasonNormalizationAndUnicodeLengthArePreserved() throws Exception {
        String reason = "\uD83D\uDE00".repeat(200);
        when(service.recover(OPERATOR, 11, KEY, reason)).thenReturn(Map.of("status", "ACCEPTED"));
        String body = new ObjectMapper().writeValueAsString(Map.of("reason", "  " + reason + "  "));
        mvc.perform(recover(body)).andExpect(status().isAccepted());
        verify(service).recover(OPERATOR, 11, KEY, reason);
        clearInvocations(service);
        invalid(recover(new ObjectMapper().writeValueAsString(Map.of("reason", reason + "a"))));
    }

    @Test void idempotencyKeyMustBeSingleAndMatchTheStrictGrammar() throws Exception {
        invalid(recover("{\"reason\":\"retry\"}").header("Idempotency-Key", KEY));
        invalid(recover("{\"reason\":\"retry\"}").with(request -> {
            // Spring's mock normalizes Content-Type; expose the duplicate servlet values explicitly.
            MockHttpServletRequest duplicate = spy(request);
            doAnswer(ignored -> Collections.enumeration(List.of("application/json", "application/json")))
                    .when(duplicate).getHeaders("Content-Type");
            return duplicate;
        }));
        for (String key : new String[]{"short", "x".repeat(65), "space in key", "key-0001,key-0001"}) {
            invalid(post(PREFIX + "/payment-attempts/11/recover").header("Authorization", OPERATOR_TOKEN)
                    .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"retry\"}"));
        }
        invalid(post(PREFIX + "/payment-attempts/11/recover").header("Authorization", OPERATOR_TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"retry\"}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "4", "1.0", "1e0", "\"1\"", "null", "true", "999999999999999999999"})
    void redeliveryCountIsAnIntegerFromOneToThree(String deliveries) throws Exception {
        invalid(redeliver("{\"reason\":\"retry\",\"deliveries\":" + deliveries + "}"));
    }

    @Test void redeliveryRejectsMissingDuplicateUnknownOrTrailingJsonFields() throws Exception {
        for (String body : List.of("{\"reason\":\"retry\"}",
                "{\"reason\":\"retry\",\"deliveries\":1,\"deliveries\":2}",
                "{\"reason\":\"retry\",\"deliveries\":1,\"amount\":1}",
                "{\"reason\":\"retry\",\"deliveries\":1}{}")) { invalid(redeliver(body)); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345678-ABCD-1234-abcd-1234567890ab", "1-abcd-1234-abcd-1234567890ab", "12345678abcd1234abcd1234567890ab"})
    void resultAndOperationIdsRequireCanonicalLowercaseUuids(String id) throws Exception {
        invalid(post(PREFIX + "/payment-results/" + id + "/redeliver").header("Authorization", OPERATOR_TOKEN)
                .header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"retry\",\"deliveries\":1}"));
        invalid(get(PREFIX + "/operations/" + id).header("Authorization", OPERATOR_TOKEN));
    }

    static Stream<Arguments> receipts() {
        return Stream.of(Arguments.of("ACCEPTED", 202), Arguments.of("COMPLETED", 200), Arguments.of("REJECTED", 409));
    }

    @ParameterizedTest @MethodSource("receipts")
    void postStatusFollowsPersistentReceiptWhileQueryAlwaysReturns200(String state, int status) throws Exception {
        Map<String, Object> receipt = Map.of("operationId", OPERATION, "status", state);
        when(service.recover(OPERATOR, 11, KEY, "retry")).thenReturn(receipt);
        when(service.redeliver(OPERATOR, RESULT, KEY, "retry", 3)).thenReturn(receipt);
        when(service.query(OPERATOR, OPERATION)).thenReturn(receipt);
        mvc.perform(recover("{\"reason\":\"  retry  \"}")).andExpect(status().is(status))
                .andExpect(jsonPath("$.operationId").value(OPERATION)).andExpect(jsonPath("$.status").value(state));
        mvc.perform(redeliver("{\"reason\":\"  retry  \",\"deliveries\":3}")).andExpect(status().is(status));
        mvc.perform(get(PREFIX + "/operations/" + OPERATION).header("Authorization", OPERATOR_TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(state));
        verify(service).recover(OPERATOR, 11, KEY, "retry");
        verify(service).redeliver(OPERATOR, RESULT, KEY, "retry", 3);
        verify(service).query(OPERATOR, OPERATION);
        verifyNoMoreInteractions(service);
    }

    @Test void errorsDoNotExposeOrSendRawExceptionsToDiagnostics() throws Exception {
        String privateDetail = "Authorization=secret-sensitive-details body=private-reason";
        when(service.recover(any(), anyLong(), anyString(), anyString())).thenThrow(new IllegalStateException(privateDetail));
        String response = mvc.perform(recover("{\"reason\":\"retry\"}")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE")).andReturn().getResponse().getContentAsString();
        assertFalse(response.contains(privateDetail));
        ArgumentCaptor<Throwable> failure = ArgumentCaptor.forClass(Throwable.class);
        verify(diagnostics).record(failure.capture());
        assertInstanceOf(ApiFailure.class, failure.getValue());
        assertEquals("DEPENDENCY_UNAVAILABLE", failure.getValue().getMessage());
        assertNull(failure.getValue().getCause());
    }

    @Test void managementFilterLeavesOrdinaryRoutesUntouched() throws Exception {
        PaymentManagementAccess access = mock(PaymentManagementAccess.class);
        PaymentManagementFilter filter = new PaymentManagementFilter(access, diagnostics);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/payment-attempts/11");
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] continued = {false};
        filter.doFilter(request, response, (req, res) -> continued[0] = true);
        assertTrue(continued[0]);
        verifyNoInteractions(access, diagnostics);
    }
}
