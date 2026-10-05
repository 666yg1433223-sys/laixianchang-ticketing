package com.ticketing.booking.service.impl;

import com.ticketing.booking.config.LocalRuntime;
import com.ticketing.booking.config.RuntimeServices;
import com.ticketing.booking.domain.ApiFailure;
import com.ticketing.booking.repository.AuthRepository;
import com.ticketing.booking.service.AuthService;
import com.ticketing.booking.model.dto.AuthCodeDTO;
import com.ticketing.booking.model.dto.AuthLoginDTO;
import com.ticketing.booking.model.po.UserPO;
import com.ticketing.booking.model.vo.AuthCodeSentVO;
import com.ticketing.booking.model.vo.AuthLoginVO;
import com.ticketing.booking.model.vo.AuthLogoutVO;
import com.ticketing.booking.model.vo.UserVO;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/** Real Redis sessions for the local-only teaching product, independent of fixture identities. */
@Service
@Profile("local-sim")
public class AuthServiceImpl implements AuthService {
    static final long CODE_SECONDS = 120;
    static final long SEND_SECONDS = 60;
    static final long SESSION_SECONDS = 1800;
    static final String PREFIX = "ticketing:local:auth:";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Logger LOG = LoggerFactory.getLogger(AuthServiceImpl.class);
    static final DefaultRedisScript<Long> SEND = new DefaultRedisScript<>("""
        if redis.call('EXISTS',KEYS[1]) == 1 then return 0 end
        local ipCount = redis.call('INCR',KEYS[4])
        if ipCount == 1 then redis.call('EXPIRE',KEYS[4],60) end
        if ipCount > 20 then return 2 end
        local globalCount = redis.call('INCR',KEYS[5])
        if globalCount == 1 then redis.call('EXPIRE',KEYS[5],60) end
        if globalCount > 100 then return 2 end
        redis.call('SET',KEYS[1],'1','EX',ARGV[2])
        redis.call('SET',KEYS[2],ARGV[1],'EX',ARGV[3])
        redis.call('DEL',KEYS[3])
        return 1
        """, Long.class);
    static final DefaultRedisScript<Long> VERIFY = new DefaultRedisScript<>("""
        local count = tonumber(redis.call('GET',KEYS[2]) or '0')
        if count >= 5 then return 2 end
        local stored = redis.call('GET',KEYS[1])
        if not stored or stored ~= ARGV[1] then
            count = redis.call('INCR',KEYS[2])
            if count == 1 then redis.call('EXPIRE',KEYS[2],120) end
            if count >= 5 then redis.call('DEL',KEYS[1]); return 2 end
            return 0
        end
        redis.call('DEL',KEYS[1],KEYS[2])
        return 1
        """, Long.class);
    static final DefaultRedisScript<String> REFRESH = new DefaultRedisScript<>("""
        local user = redis.call('GET',KEYS[1])
        if user then redis.call('EXPIRE',KEYS[1],ARGV[1]) end
        return user
        """, String.class);

    private final StringRedisTemplate redis;
    private final AuthRepository users;
    private final String adminPhone;

    public AuthServiceImpl(StringRedisTemplate redis, AuthRepository users, RuntimeServices runtime,
            @Value("${biz.local.admin-phone:}") String adminPhone) {
        this.redis = redis;
        this.users = users;
        this.adminPhone = adminPhone;
        if (!(runtime instanceof LocalRuntime local)) { throw new IllegalStateException("LOCAL_IDENTITY_ONLY"); }
        if (!adminPhone.isEmpty()) { phone(adminPhone); }
        local.bindIdentity(this::authenticate);
    }

    @Override
    public AuthCodeSentVO sendCode(AuthCodeDTO input, String remoteAddress) {
        String phone = input == null ? null : input.phone();
        phone(phone);
        String code = String.format(java.util.Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
        String key = phoneKey(phone);
        try {
            Long result = redis.execute(SEND, List.of(key + ":send", key + ":code", key + ":fail",
                PREFIX + "send:ip:" + digest(remoteAddress == null ? "unknown" : remoteAddress), PREFIX + "send:global"),
                codeDigest(phone, code), Long.toString(SEND_SECONDS), Long.toString(CODE_SECONDS));
            if (Long.valueOf(0).equals(result)) { throw new ApiFailure(429, "CODE_SEND_TOO_FREQUENT", 60); }
            if (Long.valueOf(2).equals(result)) { throw new ApiFailure(429, "CODE_SEND_RATE_LIMITED", 60); }
            if (!Long.valueOf(1).equals(result)) { throw unavailable(); }
            // This is deliberately NOT a real SMS integration. Neither full phone nor token is logged.
            LOG.info("LOCAL_SIM_SMS phone={} code={} expiresInSeconds=120 (no real SMS) phoneRef={}",
                mask(phone), code, digest(phone));
            return new AuthCodeSentVO(true, CODE_SECONDS, SEND_SECONDS, "LOCAL_LOG");
        } catch (ApiFailure failure) { throw failure; }
        catch (RuntimeException failure) { throw unavailable(); }
    }

    @Override
    public AuthLoginVO login(AuthLoginDTO input) {
        String phone = input == null ? null : input.phone();
        String code = input == null ? null : input.code();
        phone(phone);
        if (code == null || !code.matches("[0-9]{6}")) { throw new ApiFailure(400, "INVALID_ARGUMENT"); }
        String key = phoneKey(phone);
        try {
            Long result = redis.execute(VERIFY, List.of(key + ":code", key + ":fail"), codeDigest(phone, code));
            if (Long.valueOf(2).equals(result)) { throw new ApiFailure(429, "CODE_ATTEMPTS_EXCEEDED", 120); }
            if (Long.valueOf(0).equals(result)) { throw new ApiFailure(400, "CODE_INVALID_OR_EXPIRED"); }
            if (!Long.valueOf(1).equals(result)) { throw unavailable(); }
            // Unique phone serializes simultaneous registrations; no client-supplied role is accepted.
            users.registerIfAbsent(UUID.randomUUID().toString(),
                "观众" + phone.substring(7), phone, phone.equals(adminPhone) ? "ADMIN" : "USER");
            UserPO user = users.findByPhone(phone);
            if (user == null) { throw new IllegalStateException("MISSING_FACT"); }
            byte[] entropy = new byte[32]; RANDOM.nextBytes(entropy);
            String token = HexFormat.of().formatHex(entropy);
            redis.opsForValue().set(tokenKey(token), Long.toString(user.id()), Duration.ofSeconds(SESSION_SECONDS));
            return new AuthLoginVO(token, "Bearer", SESSION_SECONDS, view(user));
        } catch (ApiFailure failure) { throw failure; }
        catch (RuntimeException failure) { throw unavailable(); }
    }

    @Override
    public long authenticate(String authorization) {
        String token = bearer(authorization);
        try {
            String user = redis.execute(REFRESH, List.of(tokenKey(token)), Long.toString(SESSION_SECONDS));
            if (user == null || !user.matches("[1-9][0-9]{0,18}")) { throw new ApiFailure(401, "UNAUTHENTICATED"); }
            long id;
            try { id = Long.parseLong(user); }
            catch (NumberFormatException invalid) { throw new ApiFailure(401, "UNAUTHENTICATED"); }
            return id;
        } catch (ApiFailure failure) { throw failure; }
        catch (RuntimeException failure) { throw unavailable(); }
    }

    @Override
    public UserVO me(long user) {
        UserPO row = users.findById(user);
        if (row == null) { throw new ApiFailure(401, "UNAUTHENTICATED"); }
        return view(row);
    }

    @Override
    public void requireAdmin(long user) {
        if (!"ADMIN".equals(me(user).role())) { throw new ApiFailure(403, "ADMIN_REQUIRED"); }
    }

    @Override
    public AuthLogoutVO logout(String authorization) {
        String token = bearer(authorization);
        try {
            redis.delete(tokenKey(token));
            return new AuthLogoutVO(true);
        } catch (RuntimeException failure) { throw unavailable(); }
    }

    private UserVO view(UserPO row) {
        boolean admin = !adminPhone.isEmpty() && adminPhone.equals(row.phone()) && "ADMIN".equals(row.role());
        return new UserVO(Long.toString(row.id()), row.nickname(), admin ? "ADMIN" : "USER");
    }
    static void phone(String value) {
        if (value == null || !value.matches("1[3-9][0-9]{9}")) { throw new ApiFailure(400, "INVALID_PHONE"); }
    }
    static String bearer(String authorization) {
        if (authorization == null || !authorization.matches("Bearer [a-f0-9]{64}")) {
            throw new ApiFailure(401, "UNAUTHENTICATED");
        }
        return authorization.substring(7);
    }
    static String mask(String phone) { return phone.substring(0, 3) + "****" + phone.substring(7); }
    static String phoneKey(String phone) { return PREFIX + "phone:" + digest(phone); }
    static String tokenKey(String token) { return PREFIX + "token:" + digest(token); }
    static String codeDigest(String phone, String code) { return digest(phone + ":" + code); }
    static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static ApiFailure unavailable() { return new ApiFailure(503, "DEPENDENCY_UNAVAILABLE"); }
}
