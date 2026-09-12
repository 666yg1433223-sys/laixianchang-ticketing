package com.ticketing.booking.catalogue;

import com.ticketing.booking.domain.ApiFailure;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CatalogueCacheOfflineTest {
    StringRedisTemplate redis; ValueOperations<String,String> values; RedissonClient redisson; RLock lock; CatalogueCache cache;
    @BeforeEach @SuppressWarnings("unchecked") void setup() throws Exception {
        redis=mock(StringRedisTemplate.class);values=mock(ValueOperations.class);redisson=mock(RedissonClient.class);lock=mock(RLock.class);
        when(redis.opsForValue()).thenReturn(values);when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(200,8_000,TimeUnit.MILLISECONDS)).thenReturn(true);when(lock.isHeldByCurrentThread()).thenReturn(true);
        cache=new CatalogueCache(redis,redisson);
    }
    @Test void validHitNeverLoadsDatabaseOrTakesLock() {
        when(values.get(anyString())).thenReturn("{\"version\":1,\"data\":{\"eventId\":\"5\"}}");
        assertEquals("5",cache.event(5,()->{throw new AssertionError();}).get("eventId"));
        verifyNoInteractions(redisson);
    }
    @Test void absentMarkerIsNotCacheMiss() {
        when(values.get(anyString())).thenReturn("{\"absent\":true}");
        assertEquals(404,assertThrows(ApiFailure.class,()->cache.event(5,()->{throw new AssertionError();})).status());
        verifyNoInteractions(redisson);
    }
    @Test void coldExistingValueHasRealJitteredTtlAndUnlocks() {
        AtomicInteger reads=new AtomicInteger();
        assertEquals("5",cache.event(5,()->{reads.incrementAndGet();return Map.of("eventId","5");}).get("eventId"));
        assertEquals(1,reads.get());
        verify(values).set(eq("booking:local:cache:event:5"),contains("\"eventId\":\"5\""),argThat((Duration d)->d.getSeconds()>=60&&d.getSeconds()<=90));
        verify(lock).unlock();
    }
    @Test void coldMissingValueGetsOnlyFifteenSeconds() {
        assertEquals(404,assertThrows(ApiFailure.class,()->cache.event(8,()->null)).status());
        verify(values).set("booking:local:cache:event:8","{\"absent\":true}",Duration.ofSeconds(15));
        verify(lock).unlock();
    }
    @Test void lostLockReturnsBusyWithoutUnboundedDatabaseFallback() throws Exception {
        when(lock.tryLock(200,8_000,TimeUnit.MILLISECONDS)).thenReturn(false);
        assertEquals("CATALOGUE_BUSY",assertThrows(ApiFailure.class,()->cache.event(5,()->{throw new AssertionError();})).code());
        verify(lock,never()).unlock();
    }
    @Test void lockIsFollowedByASecondCacheRead() {
        when(values.get(anyString())).thenReturn(null,"{\"version\":1,\"data\":{\"eventId\":\"5\"}}");
        assertEquals("5",cache.event(5,()->{throw new AssertionError();}).get("eventId"));
        verify(values,times(2)).get("booking:local:cache:event:5");
    }
    @Test void redisFailureAllowsPublicSourceRead() {
        when(values.get(anyString())).thenThrow(new IllegalStateException("connection unavailable"));
        assertEquals("5",cache.event(5,()->Map.of("eventId","5")).get("eventId"));
        verifyNoInteractions(redisson);
    }
    @Test void failedSourceIsNotRepeatedAsIfRedisHadFailed() {
        AtomicInteger queries=new AtomicInteger();
        assertEquals(503,assertThrows(ApiFailure.class,()->cache.event(5,()->{queries.incrementAndGet();throw new IllegalStateException("db");})).status());
        assertEquals(1,queries.get());
    }
    @Test void tierReferenceDataExpiresAfterFiveSeconds() {
        cache.tiers(9,()->Map.of("sessionId","9","items",java.util.List.of()));
        verify(values).set(eq("booking:local:cache:tiers:9"),anyString(),eq(Duration.ofSeconds(5)));
    }
    @Test void corruptCacheIsDeletedAndColdLoaded() {
        when(values.get(anyString())).thenReturn("not json",null);
        assertEquals("5",cache.event(5,()->Map.of("eventId","5")).get("eventId"));
        verify(redis).delete("booking:local:cache:event:5");
    }
    @Test void invalidationDeletesOnlyNamedKeys() {
        cache.invalidate(5,9L);
        verify(redis).delete("booking:local:cache:event:5");verify(redis).delete("booking:local:cache:tiers:9");
        verifyNoMoreInteractions(redis);
    }
    @Test void fallbackHasOnlyEightConcurrentDatabaseSlots() throws Exception {
        var pool=java.util.concurrent.Executors.newFixedThreadPool(8);
        var entered=new java.util.concurrent.CountDownLatch(8);var release=new java.util.concurrent.CountDownLatch(1);
        try {
            var work=new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for(int i=0;i<8;i++) { work.add(pool.submit(()->cache.boundedDatabase(()-> {
                entered.countDown();
                try { if(!release.await(5,TimeUnit.SECONDS)) { throw new AssertionError("latch timeout"); } }
                catch(InterruptedException e) { Thread.currentThread().interrupt();throw new IllegalStateException(e); }
                return 1;
            }))); }
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            assertEquals("CATALOGUE_BUSY",assertThrows(ApiFailure.class,()->cache.boundedDatabase(()->2)).code());
            release.countDown();for(var future:work) { assertEquals(1,future.get(3,TimeUnit.SECONDS)); }
            assertEquals(3,cache.boundedDatabase(()->3));
        } finally { release.countDown();pool.shutdownNow(); }
    }
}
