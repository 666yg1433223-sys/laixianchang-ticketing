package com.ticketing.booking.service;

import com.ticketing.booking.config.Bootstrap;
import com.ticketing.booking.config.RuntimeServices;
import com.ticketing.booking.domain.ApiFailure;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class EntryLimiter {
    private final StringRedisTemplate redis;
    private final RuntimeServices runtime;
    private final String prefix;
    private final DefaultRedisScript<Long> script;

    public EntryLimiter(StringRedisTemplate redis, RuntimeServices runtime, Bootstrap.Ready ready) throws Exception {
        this.redis = redis; this.runtime = runtime;
        prefix = "ticketing:biz01:it:" + ready.runId() + ":rl:";
        try (var input = new ClassPathResource("lua/booking-entry-limit.lua").getInputStream()) {
            script = new DefaultRedisScript<>(new String(input.readAllBytes(),StandardCharsets.UTF_8),Long.class);
        }
    }
    public List<String> keys(long user, long tier, Instant time) {
        String common = prefix + "{" + tier + "}:";
        long bucket = time.toEpochMilli()/10000;
        return List.of(common+"u:"+user+":"+bucket,common+"t:"+bucket);
    }
    public void check(long user, long tier, Instant time) {
        long remaining = 10000 - time.toEpochMilli()%10000;
        try {
            runtime.at("BEFORE_REDIS",0);
            var keys=keys(user,tier,time);
            runtime.redisKeys(keys);
            Long result = redis.execute(script,keys,Long.toString(remaining+1000));
            if (Long.valueOf(0).equals(result)) { throw new ApiFailure(429,"RATE_LIMITED",(int)((remaining+999)/1000)); }
            if (!Long.valueOf(1).equals(result)) { throw new ApiFailure(503,"DEPENDENCY_UNAVAILABLE"); }
        } catch (ApiFailure failure) { throw failure; }
        catch (RuntimeException failure) { throw new ApiFailure(503,"DEPENDENCY_UNAVAILABLE").causedBy(failure); }
    }
}
