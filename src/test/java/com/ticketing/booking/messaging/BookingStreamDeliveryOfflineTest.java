package com.ticketing.booking.messaging;

import com.ticketing.booking.config.Diagnostics;
import com.ticketing.booking.model.po.OutboxMessagePO;
import com.ticketing.booking.repository.OutboxMessageRepository;
import com.ticketing.booking.service.AllocationService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.mockito.Mockito.*;

class BookingStreamDeliveryOfflineTest {
    private final OutboxMessageRepository repository = mock(OutboxMessageRepository.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final StreamOperations<String, Object, Object> stream = mock(StreamOperations.class);
    private final AllocationService allocation = mock(AllocationService.class);
    private final Diagnostics diagnostics = mock(Diagnostics.class);
    private final BookingStream consumer = new BookingStream(repository, redis, allocation, diagnostics,
        mock(PlatformTransactionManager.class));

    private MapRecord<String, String, String> record() {
        return MapRecord.create(BookingStream.KEY, Map.of("eventId", "1001", "schemaVersion", "1",
            "eventType", "BOOKING_REQUESTED", "requestId", "2001", "createdAt", "2026-10-05T00:00:00.000Z",
            "traceId", "trace")).withId(RecordId.of("100-0"));
    }

    private OutboxMessagePO event(Long requestId) {
        return new OutboxMessagePO(1001, "BOOKING_REQUESTED", 2001, requestId, null,
            "{\"eventId\":\"1001\",\"schemaVersion\":1,\"eventType\":\"BOOKING_REQUESTED\",\"requestId\":\"2001\",\"createdAt\":\"2026-10-05T00:00:00.000Z\",\"traceId\":\"trace\"}",
            "DONE", 0, null, 0);
    }

    @Test void committedMessageWithLargeIdsIsProcessedAndAcknowledgedByValue() {
        when(redis.opsForStream()).thenReturn(stream);
        when(repository.find(1001)).thenReturn(event(Long.valueOf(2001)));
        when(repository.terminalBookingMessage(2001)).thenReturn(true);
        consumer.processRecord(record());
        verify(allocation).process(2001);
        verify(stream).acknowledge(BookingStream.KEY, BookingStream.GROUP, RecordId.of("100-0"));
        verify(repository, never()).recordRejectedMessage(anyString(), anyString());
        verifyNoInteractions(diagnostics);
    }

    @Test void associationMismatchIsRecordedDurablyBeforeAcknowledgement() {
        when(redis.opsForStream()).thenReturn(stream);
        when(repository.find(1001)).thenReturn(event(2002L));
        consumer.processRecord(record());
        var sequence = inOrder(repository, stream);
        sequence.verify(repository).recordRejectedMessage("100-0", "UNTRUSTED_MESSAGE");
        sequence.verify(stream).acknowledge(BookingStream.KEY, BookingStream.GROUP, RecordId.of("100-0"));
        verifyNoInteractions(allocation);
    }

    @Test void nonterminalDatabaseStateNeverAcknowledgesTheMessage() {
        when(repository.find(1001)).thenReturn(event(2001L));
        when(repository.terminalBookingMessage(2001)).thenReturn(false);
        consumer.processRecord(record());
        verify(allocation).process(2001);
        verifyNoInteractions(redis);
    }

    @Test void uncertainAllocationLeavesTheMessageAvailableForRetry() {
        when(repository.find(1001)).thenReturn(event(2001L));
        doThrow(new IllegalStateException("commit uncertain")).when(allocation).process(2001);
        consumer.processRecord(record());
        verify(diagnostics).record(any(RuntimeException.class));
        verifyNoInteractions(redis);
        verify(repository, never()).terminalBookingMessage(anyLong());
    }
}
