package com.ticketing.booking.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.booking.config.RuntimeServices;
import com.ticketing.booking.domain.ApiFailure;
import com.ticketing.booking.model.dto.CreateBookingDTO;
import com.ticketing.booking.model.po.BookingRows.Session;
import com.ticketing.booking.model.vo.*;
import com.ticketing.booking.repository.BookingRepository;
import com.ticketing.booking.repository.DatabaseTimeRepository;
import com.ticketing.booking.service.*;
import static com.ticketing.booking.domain.Values.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class BookingServiceImpl implements BookingService {
    private final BookingRepository repository;
    private final DatabaseTimeRepository clock;
    private final RuntimeServices runtime;
    private final EntryLimiter limiter;
    private final AllocationService allocation;
    private final TransactionTemplate tx;
    private final ObjectMapper json=new ObjectMapper();

    public BookingServiceImpl(BookingRepository repository,DatabaseTimeRepository clock,RuntimeServices runtime,
            EntryLimiter limiter,AllocationService allocation,PlatformTransactionManager manager) {
        this.repository=repository;this.clock=clock;this.runtime=runtime;this.limiter=limiter;this.allocation=allocation;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(2);tx.setTimeout(8);
    }
    @Override public BookingAcceptedVO accept(CreateBookingDTO command) {
        long user=command.userId(),session=command.sessionId(),tier=command.tierId();String key=command.idempotencyKey();
        if(key==null || !key.matches("[A-Za-z0-9._:-]{8,64}")) { throw new ApiFailure(400,"INVALID_ARGUMENT"); }
        byte[] hash=hash(user,session,tier);
        var prior=existing(user,key,hash);if(prior!=null) { return prior; }
        runtime.at("AFTER_IDEMPOTENCY_MISS",0);
        BookingAcceptedVO accepted;
        try {
            if(runtime.productEnabled() && repository.acceptedCount()>=1000) { throw new ApiFailure(503,"BOOKING_BACKLOG_BUSY",3); }
            var catalogue=repository.findCatalogue(session,tier);
            if(catalogue==null) { throw new ApiFailure(404,"RESOURCE_NOT_FOUND"); }
            Instant now=runtime.afterLocks(clock);window(catalogue,now);limiter.check(user,tier,now);
            runtime.at("BEFORE_T1",0);
            accepted=tx.execute(status->acceptTransaction(user,session,tier,key,hash));
        } catch(ApiFailure failure) {
            var visible=existing(user,key,hash);if(visible!=null) { return visible; }throw failure;
        } catch(DuplicateKeyException duplicate) {
            String detail=duplicate.getMostSpecificCause().getMessage();
            if(detail!=null && detail.contains("uq_request_idempotency")) { var visible=existing(user,key,hash);if(visible!=null) { return visible; } }
            throw new ApiFailure(503,"DEPENDENCY_UNAVAILABLE");
        } catch(TransactionException unknownCommit) { throw new ApiFailure(503,"ACCEPTANCE_UNKNOWN").causedBy(unknownCommit);
        } catch(DataAccessException failure) { throw new ApiFailure(503,"DEPENDENCY_UNAVAILABLE").causedBy(failure); }
        long requestId=Long.parseLong(accepted.requestId());runtime.at("T1_COMMITTED",requestId);
        if(runtime.directEnabled()) { allocation.process(requestId); }
        runtime.at("BEFORE_ACCEPT_RESPONSE",requestId);return accepted;
    }
    private BookingAcceptedVO acceptTransaction(long user,long session,long tier,String key,byte[] hash) {
        var lockedSession=repository.lockSession(session);repository.lockTier(tier,session);
        Instant acceptedAt=runtime.afterLocks(clock);window(lockedSession,acceptedAt);
        Instant deadline=earlier(acceptedAt.plusSeconds(120),lockedSession.closesAt());
        long request=repository.insertRequest(user,session,tier,key,hash,acceptedAt,deadline);
        long event=repository.insertRequestedEvent(request,acceptedAt);
        try {
            String payload=json.writeValueAsString(Map.of("schemaVersion",1,"eventId",Long.toString(event),"eventType","BOOKING_REQUESTED","requestId",Long.toString(request),"createdAt",time(acceptedAt),"traceId",UUID.randomUUID().toString()));
            one(repository.updateEventPayload(event,payload));
        } catch(java.io.IOException failure) { throw new IllegalStateException("EVENT_SERIALIZATION"); }
        runtime.at("T1_BEFORE_COMMIT",request);return envelope(request,acceptedAt,deadline);
    }
    private BookingAcceptedVO existing(long user,String key,byte[] hash) {
        var prior=repository.findIdempotency(user,key);if(prior==null) { return null; }
        if(!MessageDigest.isEqual(hash,prior.payloadHash())) { throw new ApiFailure(409,"IDEMPOTENCY_CONFLICT"); }
        return envelope(prior.id(),prior.acceptedAt(),prior.processDeadline());
    }
    private static BookingAcceptedVO envelope(long request,Instant accepted,Instant deadline) { return new BookingAcceptedVO(Long.toString(request),"/v1/booking-requests/"+request,time(accepted),time(deadline)); }
    private static byte[] hash(long user,long session,long tier) {
        try { return MessageDigest.getInstance("SHA-256").digest(("v1|booking|user="+user+"|session="+session+"|tier="+tier+"|quantity=1").getBytes(StandardCharsets.UTF_8)); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void window(Session session,Instant now) {
        if(!"OPEN".equals(session.state()) || now.isBefore(session.opensAt()) || !now.isBefore(session.closesAt())) { throw new ApiFailure(409,"SESSION_NOT_OPEN"); }
    }
    @Override public BookingStatusVO query(long user,long request) {
        var status=repository.findStatus(request,user,runtime.productEnabled());
        if(status==null) { throw new ApiFailure(404,"RESOURCE_NOT_FOUND"); }
        var row=status.request();BookingOrderVO order=null;
        if("SUCCEEDED".equals(row.status())) {
            var o=status.order();if(o==null || row.orderId()==null || row.orderId()!=o.id()) { throw new IllegalStateException("ORDER_ASSOCIATION"); }
            order=new BookingOrderVO(Long.toString(o.id()),o.status(),o.unitPriceCent(),o.quantity(),o.payableCent(),o.currency(),time(o.holdCreatedAt()),time(o.payExpiresAt()),OrderVO.timestamp(o.closedAt()),o.closeReason(),OrderVO.identifier(o.currentAttemptId()),OrderVO.identifier(o.paidAttemptId()),OrderVO.timestamp(o.paidAt()),OrderVO.productFields(o.legacy()));
        }
        return new BookingStatusVO(Long.toString(request),row.status(),row.reasonCode(),time(row.acceptedAt()),time(row.processDeadline()),time(runtime.afterLocks(clock)),order);
    }
}
