package com.ticketing.booking.auth;

import com.ticketing.booking.config.LocalRuntime;
import com.ticketing.booking.domain.ApiFailure;
import com.ticketing.booking.persistence.BookingStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthServiceOfflineTest {
    private static final String PHONE = "13800138000";
    private static final String ADMIN = "13900000000";
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final BookingStore store = mock(BookingStore.class);
    private AuthService service() { return new AuthService(redis, store, new LocalRuntime(true), ADMIN); }

    @Test void strictPhoneValidationDoesNotNormalizeUntrustedInput() {
        for (String invalid : new String[]{null, "", "138", "+8613800138000", "13800138000 ", "23800138000", "12800138000"}) {
            assertEquals(400, assertThrows(ApiFailure.class, () -> AuthService.phone(invalid)).status());
        }
        assertDoesNotThrow(() -> AuthService.phone(PHONE));
    }

    @Test void bearerFormatIsExactAndNeverAcceptsRoleOrFakeFixtureIdentity() {
        String valid = "a".repeat(64);
        assertEquals(valid, AuthService.bearer("Bearer " + valid));
        for (String invalid : new String[]{null, "", "Bearer demo-user-a", "Bearer " + valid + " ", "bearer " + valid,
                "Bearer " + "A".repeat(64), "Bearer " + valid + ",admin", "Bearer " + "a".repeat(63)}) {
            assertEquals(401, assertThrows(ApiFailure.class, () -> AuthService.bearer(invalid)).status());
        }
    }

    @Test void hashesSeparatePhonesCodesAndTokensWithoutEmbeddingPersonalValues() {
        assertFalse(AuthService.phoneKey(PHONE).contains(PHONE));
        assertFalse(AuthService.tokenKey("a".repeat(64)).contains("a".repeat(64)));
        assertNotEquals(AuthService.phoneKey(PHONE), AuthService.phoneKey(ADMIN));
        assertNotEquals(AuthService.codeDigest(PHONE, "012345"), AuthService.codeDigest(ADMIN, "012345"));
        assertEquals("138****8000", AuthService.mask(PHONE));
        assertEquals(120, AuthService.CODE_SECONDS);
        assertEquals(60, AuthService.SEND_SECONDS);
        assertEquals(1800, AuthService.SESSION_SECONDS);
    }

    @Test void invalidPhoneNeverReachesRedisOrDatabase() {
        var auth = service();
        assertThrows(ApiFailure.class, () -> auth.sendCode("invalid", "127.0.0.1"));
        assertThrows(ApiFailure.class, () -> auth.login("invalid", "123456"));
        verifyNoInteractions(redis, store);
    }

    @Test void sendingReturnsMetadataNotTheVerificationCodeAndRateLimitDoesNotTouchDatabase() {
        var auth = service();
        when(redis.execute(eq(AuthService.SEND), anyList(), anyString(), anyString(), anyString())).thenReturn(1L);
        var response = auth.sendCode(PHONE, "127.0.0.1");
        assertEquals(Boolean.TRUE, response.get("sent"));
        assertEquals("LOCAL_LOG", response.get("delivery"));
        assertFalse(response.containsKey("code"));
        assertFalse(response.containsKey("phone"));
        verifyNoInteractions(store);
        when(redis.execute(eq(AuthService.SEND), anyList(), anyString(), anyString(), anyString())).thenReturn(0L);
        var denied = assertThrows(ApiFailure.class, () -> auth.sendCode(PHONE, "127.0.0.1"));
        assertEquals(429, denied.status());
        assertEquals(60, denied.retryAfter());
    }

    @Test void missingOrRejectedCodeNeverRegistersAUser() {
        var auth = service();
        when(redis.execute(eq(AuthService.VERIFY), anyList(), anyString())).thenReturn(0L);
        assertEquals("CODE_INVALID_OR_EXPIRED", assertThrows(ApiFailure.class, () -> auth.login(PHONE, "123456")).code());
        when(redis.execute(eq(AuthService.VERIFY), anyList(), anyString())).thenReturn(2L);
        assertEquals(429, assertThrows(ApiFailure.class, () -> auth.login(PHONE, "123456")).status());
        verifyNoInteractions(store);
    }

    @Test void redisOutageFailsClosedWithoutLeakingException() {
        var auth = service();
        when(redis.execute(eq(AuthService.SEND), anyList(), anyString(), anyString(), anyString()))
            .thenThrow(new IllegalStateException("private credential should never leave this exception"));
        var failure = assertThrows(ApiFailure.class, () -> auth.sendCode(PHONE, "127.0.0.1"));
        assertEquals(503, failure.status());
        assertEquals("DEPENDENCY_UNAVAILABLE", failure.getMessage());
        assertNull(failure.getCause());
        verifyNoInteractions(store);
    }

    @Test void onlyConfiguredServerPhoneAndStoredAdminRoleCanManage() {
        var auth = service();
        when(store.optional(anyString(), eq(1L))).thenReturn(Map.of("id", 1L, "phone", PHONE, "nickname", "a", "role", "ADMIN"));
        when(store.optional(anyString(), eq(2L))).thenReturn(Map.of("id", 2L, "phone", ADMIN, "nickname", "b", "role", "USER"));
        when(store.optional(anyString(), eq(3L))).thenReturn(Map.of("id", 3L, "phone", ADMIN, "nickname", "c", "role", "ADMIN"));
        assertEquals("USER", auth.me(1).get("role"));
        assertEquals(403, assertThrows(ApiFailure.class, () -> auth.requireAdmin(1)).status());
        assertEquals(403, assertThrows(ApiFailure.class, () -> auth.requireAdmin(2)).status());
        assertDoesNotThrow(() -> auth.requireAdmin(3));
        assertFalse(auth.me(3).containsKey("phone"));
    }

    @Test void deletedIdentityCannotBeUsed() {
        var jdbc = mock(JdbcTemplate.class);
        String lookup = "SELECT id,nickname,phone,role FROM app_user WHERE id=?";
        when(jdbc.queryForList(lookup, 31L)).thenReturn(List.of());
        // Exercise the real zero-row contract: optional() returns null, not Mockito's empty Map default.
        var auth = new AuthService(redis, new BookingStore(jdbc), new LocalRuntime(true), ADMIN);
        var failure = assertThrows(ApiFailure.class, () -> auth.me(31));
        assertEquals(401, failure.status());
        assertEquals("UNAUTHENTICATED", failure.code());
        verify(jdbc).queryForList(lookup, 31L);
        verifyNoInteractions(redis);
    }

    @Test void expiredSessionCannotBeUsed() {
        var auth = service();
        when(redis.execute(eq(AuthService.REFRESH), anyList(), anyString())).thenReturn(null);
        var failure = assertThrows(ApiFailure.class, () -> auth.authenticate("Bearer " + "a".repeat(64)));
        assertEquals(401, failure.status());
        assertEquals("UNAUTHENTICATED", failure.code());
        verifyNoInteractions(store);
    }

    @Test void aLiveSessionRefreshesAndReturnsOnlyTheServerStoredUserId() {
        var local = new LocalRuntime(true);
        var auth = new AuthService(redis, store, local, ADMIN);
        when(redis.execute(eq(AuthService.REFRESH), anyList(), eq("1800"))).thenReturn("52");
        assertEquals(52L, local.authenticate("Bearer " + "a".repeat(64)));
        assertEquals(52L, auth.authenticate("Bearer " + "a".repeat(64)));
    }
}
