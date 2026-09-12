package com.ticketing.booking.catalogue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.booking.domain.ApiFailure;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Public display cache only. Neither cached stock nor this lock authorizes a purchase. */
@Component
@Profile("local-sim")
public class CatalogueCache {
    public static final String PREFIX="booking:local:cache:";
    private static final String ABSENT="{\"absent\":true}";
    private final StringRedisTemplate redis;
    private final RedissonClient redisson;
    private final ObjectMapper json=new ObjectMapper();
    private final Semaphore databaseSlots=new Semaphore(8,true);
    public CatalogueCache(StringRedisTemplate redis,RedissonClient redisson) { this.redis=redis;this.redisson=redisson; }
    public Map<String,Object> event(long id,Supplier<Map<String,Object>> loader) {
        return read("event:"+id,Duration.ofSeconds(ThreadLocalRandom.current().nextInt(60,91)),loader);
    }
    public Map<String,Object> tiers(long id,Supplier<Map<String,Object>> loader) {
        return read("tiers:"+id,Duration.ofSeconds(5),loader);
    }
    public <T> T boundedDatabase(Supplier<T> loader) {
        if(!databaseSlots.tryAcquire()) { throw new ApiFailure(503,"CATALOGUE_BUSY",1); }
        try { return loader.get(); } finally { databaseSlots.release(); }
    }
    private record Hit(boolean present,Map<String,Object> data) { }
    private Hit get(String key) {
        String raw=redis.opsForValue().get(key);
        if(raw==null) { return new Hit(false,null); }
        if(ABSENT.equals(raw)) { return new Hit(true,null); }
        try {
            var tree=json.readTree(raw);
            if(!tree.isObject()||tree.path("version").asInt()!=1||!tree.path("data").isObject()) { throw new IllegalArgumentException(); }
            return new Hit(true,json.convertValue(tree.get("data"),new TypeReference<Map<String,Object>>(){}));
        } catch(Exception invalid) { redis.delete(key);return new Hit(false,null); }
    }
    private Map<String,Object> read(String suffix,Duration ttl,Supplier<Map<String,Object>> loader) {
        String key=PREFIX+suffix;
        RLock lock=null; boolean acquired=false,sourceStarted=false;
        try {
            Hit hit=get(key); if(hit.present()) { return visible(hit.data()); }
            lock=redisson.getLock("booking:local:lock:"+suffix);
            acquired=lock.tryLock(200,8_000,TimeUnit.MILLISECONDS);
            hit=get(key); if(hit.present()) { return visible(hit.data()); }
            if(!acquired) { throw new ApiFailure(503,"CATALOGUE_BUSY",1); }
            sourceStarted=true;
            Map<String,Object> loaded=boundedDatabase(loader);
            try {
                redis.opsForValue().set(key,loaded==null?ABSENT:json.writeValueAsString(Map.of("version",1,"data",loaded)),
                    loaded==null?Duration.ofSeconds(15):ttl);
            } catch(Exception ignored) { /* The source result is valid; failed cache writes are not transaction failures. */ }
            return visible(loaded);
        } catch(ApiFailure business) { throw business; }
        catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new ApiFailure(503,"CATALOGUE_BUSY",1);
        } catch(RuntimeException redisUnavailable) {
            if(sourceStarted) { throw new ApiFailure(503,"DEPENDENCY_UNAVAILABLE").causedBy(redisUnavailable); }
            return visible(boundedDatabase(loader));
        } finally {
            if(acquired&&lock!=null) {
                try { if(lock.isHeldByCurrentThread()) { lock.unlock(); } } catch(RuntimeException ignored) { /* Lease is bounded. */ }
            }
        }
    }
    private Map<String,Object> visible(Map<String,Object> data) {
        if(data==null) { throw new ApiFailure(404,"RESOURCE_NOT_FOUND"); }
        return data;
    }
    public void invalidate(long eventId,Long sessionId) {
        redis.delete(PREFIX+"event:"+eventId);
        if(sessionId!=null) { redis.delete(PREFIX+"tiers:"+sessionId); }
    }
}
