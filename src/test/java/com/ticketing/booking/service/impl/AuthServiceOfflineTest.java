package com.ticketing.booking.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.booking.config.LocalRuntime;
import com.ticketing.booking.domain.ApiFailure;
import com.ticketing.booking.model.dto.AuthCodeDTO;
import com.ticketing.booking.model.dto.AuthLoginDTO;
import com.ticketing.booking.model.po.UserPO;
import com.ticketing.booking.repository.AuthRepository;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthServiceOfflineTest {
    private static final String PHONE = "13800138000";
    private static final String ADMIN = "13900000000";
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final AuthRepository users = mock(AuthRepository.class);
    private AuthServiceImpl service() { return new AuthServiceImpl(redis, users, new LocalRuntime(true), ADMIN); }

    @Test void strictPhoneValidationDoesNotNormalizeUntrustedInput() {
        for (String invalid : new String[]{null, "", "138", "+8613800138000", "13800138000 ", "23800138000", "12800138000"}) {
            assertEquals(400, assertThrows(ApiFailure.class, () -> AuthServiceImpl.phone(invalid)).status());
        }
        assertDoesNotThrow(() -> AuthServiceImpl.phone(PHONE));
    }

    @Test void bearerFormatIsExactAndNeverAcceptsRoleOrFakeFixtureIdentity() {
        String valid = "a".repeat(64);
        assertEquals(valid, AuthServiceImpl.bearer("Bearer " + valid));
        for (String invalid : new String[]{null, "", "Bearer demo-user-a", "Bearer " + valid + " ", "bearer " + valid,
                "Bearer " + "A".repeat(64), "Bearer " + valid + ",admin", "Bearer " + "a".repeat(63)}) {
            assertEquals(401, assertThrows(ApiFailure.class, () -> AuthServiceImpl.bearer(invalid)).status());
        }
    }

    @Test void hashesSeparatePhonesCodesAndTokensWithoutEmbeddingPersonalValues() {
        assertFalse(AuthServiceImpl.phoneKey(PHONE).contains(PHONE));
        assertFalse(AuthServiceImpl.tokenKey("a".repeat(64)).contains("a".repeat(64)));
        assertNotEquals(AuthServiceImpl.phoneKey(PHONE), AuthServiceImpl.phoneKey(ADMIN));
        assertNotEquals(AuthServiceImpl.codeDigest(PHONE, "012345"), AuthServiceImpl.codeDigest(ADMIN, "012345"));
        assertEquals("138****8000", AuthServiceImpl.mask(PHONE));
        assertEquals(120, AuthServiceImpl.CODE_SECONDS);
        assertEquals(60, AuthServiceImpl.SEND_SECONDS);
        assertEquals(1800, AuthServiceImpl.SESSION_SECONDS);
    }

    @Test void invalidPhoneNeverReachesRedisOrDatabase() {
        var auth = service();
        assertThrows(ApiFailure.class, () -> auth.sendCode(new AuthCodeDTO("invalid"), "127.0.0.1"));
        assertThrows(ApiFailure.class, () -> auth.login(new AuthLoginDTO("invalid", "123456")));
        verifyNoInteractions(redis, users);
    }

    @Test void sendingReturnsMetadataNotTheVerificationCodeAndRateLimitDoesNotTouchDatabase() {
        var auth = service();
        when(redis.execute(eq(AuthServiceImpl.SEND), anyList(), anyString(), anyString(), anyString())).thenReturn(1L);
        var response = new ObjectMapper().valueToTree(auth.sendCode(new AuthCodeDTO(PHONE), "127.0.0.1"));
        assertTrue(response.get("sent").booleanValue());
        assertEquals("LOCAL_LOG", response.get("delivery").textValue());
        assertEquals(120, response.get("expiresInSeconds").longValue());
        assertEquals(60, response.get("retryAfterSeconds").longValue());
        assertEquals(4, response.size());
        assertFalse(response.has("code"));
        assertFalse(response.has("phone"));
        verifyNoInteractions(users);
        when(redis.execute(eq(AuthServiceImpl.SEND), anyList(), anyString(), anyString(), anyString())).thenReturn(0L);
        var denied = assertThrows(ApiFailure.class, () -> auth.sendCode(new AuthCodeDTO(PHONE), "127.0.0.1"));
        assertEquals(429, denied.status());
        assertEquals(60, denied.retryAfter());
    }

    @Test void missingOrRejectedCodeNeverRegistersAUser() {
        var auth = service();
        when(redis.execute(eq(AuthServiceImpl.VERIFY), anyList(), anyString())).thenReturn(0L);
        assertEquals("CODE_INVALID_OR_EXPIRED", assertThrows(ApiFailure.class, () -> auth.login(new AuthLoginDTO(PHONE, "123456"))).code());
        when(redis.execute(eq(AuthServiceImpl.VERIFY), anyList(), anyString())).thenReturn(2L);
        assertEquals(429, assertThrows(ApiFailure.class, () -> auth.login(new AuthLoginDTO(PHONE, "123456"))).status());
        verifyNoInteractions(users);
    }

    @Test void redisOutageFailsClosedWithoutLeakingException() {
        var auth = service();
        when(redis.execute(eq(AuthServiceImpl.SEND), anyList(), anyString(), anyString(), anyString()))
            .thenThrow(new IllegalStateException("private credential should never leave this exception"));
        var failure = assertThrows(ApiFailure.class, () -> auth.sendCode(new AuthCodeDTO(PHONE), "127.0.0.1"));
        assertEquals(503, failure.status());
        assertEquals("DEPENDENCY_UNAVAILABLE", failure.getMessage());
        assertNull(failure.getCause());
        verifyNoInteractions(users);
    }

    @Test void onlyConfiguredServerPhoneAndStoredAdminRoleCanManage() {
        var auth = service();
        when(users.findById(1L)).thenReturn(new UserPO(1L, "a", PHONE, "ADMIN"));
        when(users.findById(2L)).thenReturn(new UserPO(2L, "b", ADMIN, "USER"));
        when(users.findById(3L)).thenReturn(new UserPO(3L, "c", ADMIN, "ADMIN"));
        assertEquals("USER", auth.me(1).role());
        assertEquals(403, assertThrows(ApiFailure.class, () -> auth.requireAdmin(1)).status());
        assertEquals(403, assertThrows(ApiFailure.class, () -> auth.requireAdmin(2)).status());
        assertDoesNotThrow(() -> auth.requireAdmin(3));
        assertFalse(new ObjectMapper().valueToTree(auth.me(3)).has("phone"));
    }

    @Test void deletedIdentityCannotBeUsed() {
        var jdbc = mock(JdbcTemplate.class);
        String lookup = "SELECT id,nickname,phone,role FROM app_user WHERE id=?";
        when(jdbc.query(eq(lookup), org.mockito.ArgumentMatchers.<RowMapper<UserPO>>any(), eq(31L))).thenReturn(List.of());
        // Exercise the real zero-row contract rather than relying on a mocked repository result.
        var auth = new AuthServiceImpl(redis, new AuthRepository(jdbc), new LocalRuntime(true), ADMIN);
        var failure = assertThrows(ApiFailure.class, () -> auth.me(31));
        assertEquals(401, failure.status());
        assertEquals("UNAUTHENTICATED", failure.code());
        verify(jdbc).query(eq(lookup), org.mockito.ArgumentMatchers.<RowMapper<UserPO>>any(), eq(31L));
        verifyNoInteractions(redis);
    }

    @Test void expiredSessionCannotBeUsed() {
        var auth = service();
        when(redis.execute(eq(AuthServiceImpl.REFRESH), anyList(), anyString())).thenReturn(null);
        var failure = assertThrows(ApiFailure.class, () -> auth.authenticate("Bearer " + "a".repeat(64)));
        assertEquals(401, failure.status());
        assertEquals("UNAUTHENTICATED", failure.code());
        verifyNoInteractions(users);
    }

    @Test void aLiveSessionRefreshesAndReturnsOnlyTheServerStoredUserId() {
        var local = new LocalRuntime(true);
        var auth = new AuthServiceImpl(redis, users, local, ADMIN);
        when(redis.execute(eq(AuthServiceImpl.REFRESH), anyList(), eq("1800"))).thenReturn("52");
        assertEquals(52L, local.authenticate("Bearer " + "a".repeat(64)));
        assertEquals(52L, auth.authenticate("Bearer " + "a".repeat(64)));
    }

    @Test @SuppressWarnings("unchecked") void successfulLoginRetainsStringIdsAndNeverExposesStoredPhone() {
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.execute(eq(AuthServiceImpl.VERIFY), anyList(), anyString())).thenReturn(1L);
        when(users.findByPhone(PHONE)).thenReturn(new UserPO(9007199254740993L, "观众8000", PHONE, "USER"));
        var result = service().login(new AuthLoginDTO(PHONE, "012345"));
        assertTrue(result.token().matches("[a-f0-9]{64}"));
        assertEquals("9007199254740993", result.user().id());
        verify(users).registerIfAbsent(anyString(), eq("观众8000"), eq(PHONE), eq("USER"));
        verify(values).set(AuthServiceImpl.tokenKey(result.token()), "9007199254740993", Duration.ofSeconds(1800));
        var json = new ObjectMapper().valueToTree(result);
        Set<String> expected = Set.of("token", "tokenType", "expiresInSeconds", "user");
        var fields = new java.util.HashSet<String>();
        json.fieldNames().forEachRemaining(fields::add);
        assertEquals(expected, fields);
        assertEquals(3, json.get("user").size());
        assertFalse(json.get("user").has("phone"));
    }
}
