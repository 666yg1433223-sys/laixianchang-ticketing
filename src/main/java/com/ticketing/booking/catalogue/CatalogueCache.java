package com.ticketing.booking.catalogue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.booking.domain.ApiFailure;
import com.ticketing.booking.model.vo.EventDetailVO;
import com.ticketing.booking.model.vo.SessionTiersVO;
import java.time.Duration;
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
    public EventDetailVO event(long id,Supplier<EventDetailVO> loader) {
        return read("event:"+id,Duration.ofSeconds(ThreadLocalRandom.current().nextInt(60,91)),EventDetailVO.class,loader);
    }
    public SessionTiersVO tiers(long id,Supplier<SessionTiersVO> loader) {
        return read("tiers:"+id,Duration.ofSeconds(5),SessionTiersVO.class,loader);
    }
    public <T> T boundedDatabase(Supplier<T> loader) {
        if(!databaseSlots.tryAcquire()) { throw new ApiFailure(503,"CATALOGUE_BUSY",1); }
        try { return loader.get(); } finally { databaseSlots.release(); }
    }
    private record Hit<T>(boolean present,T data) { }
    private record Envelope<T>(int version,T data) { }
    private <T> Hit<T> get(String key,Class<T> type) {
        String raw=redis.opsForValue().get(key);
        if(raw==null) { return new Hit<>(false,null); }
        if(ABSENT.equals(raw)) { return new Hit<>(true,null); }
        try {
            var tree=json.readTree(raw);
            if(!tree.isObject()||tree.path("version").asInt()!=1||!tree.path("data").isObject()) { throw new IllegalArgumentException(); }
            return new Hit<>(true,json.treeToValue(tree.get("data"),type));
        } catch(Exception invalid) { redis.delete(key);return new Hit<>(false,null); }
    }
    private <T> T read(String suffix,Duration ttl,Class<T> type,Supplier<T> loader) {
        String key=PREFIX+suffix;
        RLock lock=null; boolean acquired=false,sourceStarted=false;
        try {
            Hit<T> hit=get(key,type); if(hit.present()) { return visible(hit.data()); }
            lock=redisson.getLock("booking:local:lock:"+suffix);
            acquired=lock.tryLock(200,8_000,TimeUnit.MILLISECONDS);
            hit=get(key,type); if(hit.present()) { return visible(hit.data()); }
            if(!acquired) { throw new ApiFailure(503,"CATALOGUE_BUSY",1); }
            sourceStarted=true;
            T loaded=boundedDatabase(loader);
            try {
                redis.opsForValue().set(key,loaded==null?ABSENT:json.writeValueAsString(new Envelope<>(1,loaded)),
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
    private <T> T visible(T data) {
        if(data==null) { throw new ApiFailure(404,"RESOURCE_NOT_FOUND"); }
        return data;
    }
    public void invalidate(long eventId,Long sessionId) {
        redis.delete(PREFIX+"event:"+eventId);
        if(sessionId!=null) { redis.delete(PREFIX+"tiers:"+sessionId); }
    }
}
