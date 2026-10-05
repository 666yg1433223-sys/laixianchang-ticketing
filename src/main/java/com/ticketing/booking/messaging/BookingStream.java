package com.ticketing.booking.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.booking.config.Diagnostics;
import com.ticketing.booking.repository.OutboxMessageRepository;
import com.ticketing.booking.service.AllocationService;
import static com.ticketing.booking.domain.Values.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** At-least-once delivery. Only a committed MySQL terminal state permits ACK. */
@Component
@Profile("local-sim")
public class BookingStream {
    public static final String KEY="booking:local:stream:requests", GROUP="booking-v1";
    private final String consumer="instance-"+UUID.randomUUID();
    private final OutboxMessageRepository repository;private final StringRedisTemplate redis;
    private final AllocationService allocation;private final Diagnostics diagnostics;
    private final TransactionTemplate tx;private final ObjectMapper json=new ObjectMapper();
    private String claimCursor="0-0";
    private volatile String lastClaimFailure="";
    public String lastClaimFailure() { return lastClaimFailure; }
    public BookingStream(OutboxMessageRepository repository,StringRedisTemplate redis,AllocationService allocation,Diagnostics diagnostics,PlatformTransactionManager manager) {
        this.repository=repository;this.redis=redis;this.allocation=allocation;this.diagnostics=diagnostics;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(2);tx.setTimeout(8);
    }
    @Scheduled(fixedDelay=500,initialDelay=1500)
    public synchronized void dispatch() {
        try {
            ensureGroup();
            var ids=repository.readyBookingMessages();
            for(long id:ids) { publish(id); }
        } catch(RuntimeException e) { diagnostics.record(e); }
    }
    public void ensureGroup() {
        try {
            redis.execute((RedisCallback<String>) c -> c.streamCommands().xGroupCreate(bytes(KEY),GROUP,ReadOffset.from("0-0"),true));
        } catch(RuntimeException e) {
            Throwable cause=e;while(cause.getCause()!=null) { cause=cause.getCause(); }
            if(cause.getMessage()==null || !cause.getMessage().contains("BUSYGROUP")) { throw e; }
        }
    }
    public void publish(long id) {
        String lease=UUID.randomUUID().toString();
        var event=tx.execute(status -> {
            boolean claimed=repository.claimBookingMessage(id,lease);
            return claimed?repository.require(id):null;
        });
        if(event==null) { return; }
        try {
            var record=new LinkedHashMap<String,String>();
            json.readTree(event.payload()).fields().forEachRemaining(e -> record.put(e.getKey(),e.getValue().asText()));
            if(redis.opsForStream().add(KEY,record)==null) { throw new IllegalStateException("STREAM_PUBLISH_FAILED"); }
            // Consumer may already have committed DONE; do not resurrect the event.
            repository.published(id,lease);
        } catch(Exception e) {
            repository.publicationFailed(id,lease);
            diagnostics.record(e);
        }
    }
    @Scheduled(fixedDelay=300,initialDelay=2000)
    public synchronized void consume() {
        try {
            ensureGroup();
            var records=redis.<String,String>opsForStream().read(Consumer.from(GROUP,consumer),StreamReadOptions.empty().count(20),StreamOffset.create(KEY,ReadOffset.lastConsumed()));
            if(records!=null) { for(var record:records) { processRecord(record); } }
        } catch(RuntimeException e) { diagnostics.record(e); }
    }
    @Scheduled(fixedDelay=3000,initialDelay=5000)
    public synchronized void reclaim() {
        try {
            ensureGroup();
            // 60s exceeds the 8s business transaction timeout and bounded retry backoff.
            // Spring's untyped execute decoder does not preserve XAUTOCLAIM's nested reply.
            // Use the already-configured Lettuce connection and its typed command decoder.
            var claimed=redis.execute((RedisCallback<io.lettuce.core.models.stream.ClaimedMessages<byte[],byte[]>>) c -> {
                var commands=(io.lettuce.core.api.async.RedisAsyncCommands<byte[],byte[]>)c.getNativeConnection();
                var args=new io.lettuce.core.XAutoClaimArgs<byte[]>().consumer(io.lettuce.core.Consumer.from(bytes(GROUP),bytes(consumer)))
                    .minIdleTime(Duration.ofSeconds(60)).startId(claimCursor).count(20);
                try { return commands.xautoclaim(bytes(KEY),args).get(2,java.util.concurrent.TimeUnit.SECONDS); }
                catch(InterruptedException e) { Thread.currentThread().interrupt();throw new IllegalStateException("STREAM_CLAIM_INTERRUPTED"); }
                catch(Exception e) { throw new IllegalStateException("STREAM_CLAIM_FAILED",e); }
            });
            if(claimed==null) { throw new IllegalStateException("STREAM_CLAIM_EMPTY_REPLY"); }
            claimCursor=claimed.getId();
            for(var message:claimed.getMessages()) {
                var value=new LinkedHashMap<String,String>();message.getBody().forEach((k,v)->value.put(text(k),text(v)));
                processRecord(MapRecord.create(KEY,value).withId(RecordId.of(message.getId())));
            }
            // Redis can report IDs deleted externally. Unfinished DB events are still republished.
            lastClaimFailure="";
        } catch(RuntimeException e) {
            Throwable cause=e;while(cause.getCause()!=null) { cause=cause.getCause(); }
            lastClaimFailure=e.getClass().getSimpleName()+"/"+cause.getClass().getSimpleName();diagnostics.record(e);
        }
    }
    public void processRecord(MapRecord<String,String,String> record) {
        try {
            var fields=record.getValue();Long eventId=null,requestId=null;
            try {
                if(!fields.keySet().equals(Set.of("eventId","schemaVersion","eventType","requestId","createdAt","traceId"))
                    || !"1".equals(fields.get("schemaVersion")) || !"BOOKING_REQUESTED".equals(fields.get("eventType"))) { throw new IllegalArgumentException(); }
                eventId=id(fields.get("eventId"));requestId=id(fields.get("requestId"));
            } catch(RuntimeException invalid) { reject(record,"INVALID_MESSAGE");return; }
            var event=repository.find(eventId);
            if(event==null || !"BOOKING_REQUESTED".equals(event.eventType()) || event.aggregateId()!=requestId
                || !Objects.equals(event.bookingRequestId(),requestId) || !matches(event.payload(),fields)) {
                reject(record,"UNTRUSTED_MESSAGE");return;
            }
            allocation.process(requestId);
            if(repository.terminalBookingMessage(requestId)) {
                redis.opsForStream().acknowledge(KEY,GROUP,record.getId());
            }
        } catch(RuntimeException failure) { diagnostics.record(failure); } // no ACK on uncertainty
    }
    private boolean matches(String stored,Map<String,String> fields) {
        try {
            var node=json.readTree(stored);if(node.size()!=fields.size()) { return false; }
            for(var entry:fields.entrySet()) { if(!entry.getValue().equals(node.path(entry.getKey()).asText())) { return false; } }
            return true;
        } catch(Exception e) { return false; }
    }
    private void reject(MapRecord<String,String,String> record,String reason) {
        // Store only a bounded classification, never arbitrary payload/phone/token values. Durable before ACK.
        repository.recordRejectedMessage(record.getId().getValue(),reason);
        redis.opsForStream().acknowledge(KEY,GROUP,record.getId());
    }
    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private static String text(Object value) { return value instanceof byte[] b?new String(b,StandardCharsets.UTF_8):value.toString(); }
}
