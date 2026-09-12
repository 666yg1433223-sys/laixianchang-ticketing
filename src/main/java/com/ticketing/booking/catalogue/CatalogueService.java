package com.ticketing.booking.catalogue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.booking.auth.AuthService;
import com.ticketing.booking.config.RuntimeServices;
import com.ticketing.booking.domain.ApiFailure;
import com.ticketing.booking.persistence.BookingStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.ticketing.booking.domain.Values.*;
import static com.ticketing.booking.catalogue.CatalogueInput.*;

@Service
@Profile("local-sim")
public class CatalogueService {
    private static final Set<String> EVENT=Set.of("title","description","venueText");
    private static final Set<String> SESSION=Set.of("eventId","startAt","bookingOpenAt","bookingCloseAt");
    private static final Set<String> SESSION_EDIT=Set.of("startAt","bookingOpenAt","bookingCloseAt");
    private static final Set<String> TIER=Set.of("sessionId","name","areaLabel","capacity","unitPriceCent");
    private static final Set<String> TIER_EDIT=Set.of("name","areaLabel","capacity","unitPriceCent");
    private final BookingStore store; private final RuntimeServices runtime; private final AuthService auth;
    private final CatalogueCache cache; private final CatalogueInvalidator invalidator; private final TransactionTemplate tx;
    public CatalogueService(BookingStore store,RuntimeServices runtime,AuthService auth,CatalogueCache cache,
            CatalogueInvalidator invalidator,PlatformTransactionManager manager) {
        this.store=store;this.runtime=runtime;this.auth=auth;this.cache=cache;this.invalidator=invalidator;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(2);tx.setTimeout(8);
    }
    public Map<String,Object> events(int page,int size) {
        return cache.boundedDatabase(()-> {
            var rows=store.jdbc().queryForList("SELECT e.* FROM event e WHERE EXISTS (SELECT 1 FROM event_session s WHERE s.event_id=e.id AND s.state IN ('OPEN','PAUSED')) ORDER BY e.id DESC LIMIT ? OFFSET ?",size+1,(page-1)*size);
            return page(rows.stream().limit(size).map(this::eventView).toList(),page,size,rows.size()>size);
        });
    }
    public Map<String,Object> event(long eventId) {
        return cache.event(eventId,()-> {
            var row=store.optional("SELECT e.* FROM event e WHERE e.id=? AND EXISTS (SELECT 1 FROM event_session s WHERE s.event_id=e.id AND s.state IN ('OPEN','PAUSED'))",eventId);
            if(row==null) { return null; }
            var view=eventView(row);
            view.put("sessions",store.jdbc().queryForList("SELECT * FROM event_session WHERE event_id=? AND state IN ('OPEN','PAUSED') ORDER BY start_at,id LIMIT 100",eventId).stream().map(this::sessionView).toList());
            return view;
        });
    }
    public Map<String,Object> tiers(long sessionId) {
        return cache.tiers(sessionId,()-> {
            var session=store.optional("SELECT * FROM event_session WHERE id=? AND state IN ('OPEN','PAUSED')",sessionId);
            if(session==null) { return null; }
            var result=sessionView(session);
            result.put("items",store.jdbc().queryForList("SELECT * FROM ticket_tier WHERE session_id=? ORDER BY id LIMIT 100",sessionId).stream().map(this::tierView).toList());
            result.put("availabilityNotice","余量仅供参考，实际库存、停售时间及每人限额以受理后的订单结果为准。");
            return result;
        });
    }
    public Map<String,Object> adminEvents(long user,int page,int size) {
        auth.requireAdmin(user);
        var rows=store.jdbc().queryForList("SELECT * FROM event ORDER BY id DESC LIMIT ? OFFSET ?",size+1,(page-1)*size);
        List<Map<String,Object>> items=new ArrayList<>();
        for(var row:rows.stream().limit(size).toList()) {
            var event=eventView(row);
            List<Map<String,Object>> sessions=new ArrayList<>();
            for(var s:store.jdbc().queryForList("SELECT * FROM event_session WHERE event_id=? ORDER BY id LIMIT 100",number(row,"id"))) {
                var session=sessionView(s);
                session.put("ticketTiers",store.jdbc().queryForList("SELECT * FROM ticket_tier WHERE session_id=? ORDER BY id LIMIT 100",number(s,"id")).stream().map(this::tierView).toList());
                sessions.add(session);
            }
            event.put("sessions",sessions);items.add(event);
        }
        return page(items,page,size,rows.size()>size);
    }
    public Map<String,Object> createEvent(long user,String raw) {
        auth.requireAdmin(user);var input=body(raw,EVENT,Set.of("title"));
        String title=text(input,"title",128,false);
        String description=input.has("description")?text(input,"description",5000,true):"";
        String venue=input.has("venueText")?text(input,"venueText",255,true):"";
        return mutate(()-> {
            long id=store.insert("INSERT INTO event(title,description,venue_text) VALUES(?,?,?)",title,description,venue);
            return changed(id,null,eventView(store.required("SELECT * FROM event WHERE id=?",id)));
        });
    }
    public Map<String,Object> editEvent(long user,long eventId,String raw) {
        auth.requireAdmin(user);var input=body(raw,EVENT,Set.of());if(input.isEmpty()) { throw invalid(); }
        return mutate(()-> {
            var row=required("SELECT * FROM event WHERE id=? FOR UPDATE",eventId);
            String title=input.has("title")?text(input,"title",128,false):(String)row.get("title");
            String description=input.has("description")?text(input,"description",5000,true):(String)row.get("description");
            String venue=input.has("venueText")?text(input,"venueText",255,true):(String)row.get("venue_text");
            store.jdbc().update("UPDATE event SET title=?,description=?,venue_text=?,updated_at=CURRENT_TIMESTAMP(3) WHERE id=?",title,description,venue,eventId);
            return changed(eventId,null,eventView(store.required("SELECT * FROM event WHERE id=?",eventId)));
        });
    }
    public Map<String,Object> createSession(long user,String raw) {
        auth.requireAdmin(user);var input=body(raw,SESSION,SESSION);
        long eventId=id(input,"eventId"); Instant start=instant(input,"startAt"),open=instant(input,"bookingOpenAt"),close=instant(input,"bookingCloseAt");
        return mutate(()-> {
            required("SELECT id FROM event WHERE id=? FOR UPDATE",eventId);
            window(open,close,start,runtime.afterLocks(store.jdbc()));
            long count=store.jdbc().queryForObject("SELECT COUNT(*) FROM event_session WHERE event_id=?",Long.class,eventId);
            if(count>=100) { throw new ApiFailure(409,"CATALOGUE_LIMIT_REACHED"); }
            long sid=store.insert("INSERT INTO event_session(event_id,start_at,booking_open_at,booking_close_at,state) VALUES(?,?,?,?,'DRAFT')",eventId,sqlTime(start),sqlTime(open),sqlTime(close));
            return changed(eventId,sid,sessionView(store.required("SELECT * FROM event_session WHERE id=?",sid)));
        });
    }
    public Map<String,Object> editSession(long user,long sessionId,String raw) {
        auth.requireAdmin(user);var input=body(raw,SESSION_EDIT,Set.of());if(input.isEmpty()) { throw invalid(); }
        return mutate(()-> {
            var row=required("SELECT * FROM event_session WHERE id=? FOR UPDATE",sessionId);draft(row);
            Instant start=input.has("startAt")?instant(input,"startAt"):instant(row.get("start_at"));
            Instant open=input.has("bookingOpenAt")?instant(input,"bookingOpenAt"):instant(row.get("booking_open_at"));
            Instant close=input.has("bookingCloseAt")?instant(input,"bookingCloseAt"):instant(row.get("booking_close_at"));
            window(open,close,start,runtime.afterLocks(store.jdbc()));
            store.jdbc().update("UPDATE event_session SET start_at=?,booking_open_at=?,booking_close_at=? WHERE id=?",sqlTime(start),sqlTime(open),sqlTime(close),sessionId);
            return changed(number(row,"event_id"),sessionId,sessionView(store.required("SELECT * FROM event_session WHERE id=?",sessionId)));
        });
    }
    public Map<String,Object> createTier(long user,String raw) {
        auth.requireAdmin(user);var input=body(raw,TIER,Set.of("sessionId","name","capacity","unitPriceCent"));
        long sid=id(input,"sessionId");String name=text(input,"name",64,false),area=input.has("areaLabel")?text(input,"areaLabel",64,true):"";
        int capacity=integer(input,"capacity",1,1_000_000),price=integer(input,"unitPriceCent",1,1_000_000);
        return mutate(()-> {
            var session=required("SELECT * FROM event_session WHERE id=? FOR UPDATE",sid);draft(session);
            long count=store.jdbc().queryForObject("SELECT COUNT(*) FROM ticket_tier WHERE session_id=?",Long.class,sid);
            if(count>=100) { throw new ApiFailure(409,"CATALOGUE_LIMIT_REACHED"); }
            long tid=store.insert("INSERT INTO ticket_tier(session_id,name,area_label,capacity,available,unit_price_cent,currency) VALUES(?,?,?,?,?,?,'CNY')",sid,name,area,capacity,capacity,price);
            return changed(number(session,"event_id"),sid,tierView(store.required("SELECT * FROM ticket_tier WHERE id=?",tid)));
        });
    }
    public Map<String,Object> editTier(long user,long tierId,String raw) {
        auth.requireAdmin(user);var input=body(raw,TIER_EDIT,Set.of());if(input.isEmpty()) { throw invalid(); }
        return mutate(()-> {
            var route=required("SELECT session_id FROM ticket_tier WHERE id=?",tierId);long sid=number(route,"session_id");
            var session=required("SELECT * FROM event_session WHERE id=? FOR UPDATE",sid);
            var row=required("SELECT * FROM ticket_tier WHERE id=? AND session_id=? FOR UPDATE",tierId,sid);
            if(input.has("capacity")||input.has("unitPriceCent")) { draft(session); }
            String name=input.has("name")?text(input,"name",64,false):(String)row.get("name");
            String area=input.has("areaLabel")?text(input,"areaLabel",64,true):(String)row.get("area_label");
            int capacity=input.has("capacity")?integer(input,"capacity",1,1_000_000):((Number)row.get("capacity")).intValue();
            int price=input.has("unitPriceCent")?integer(input,"unitPriceCent",1,1_000_000):((Number)row.get("unit_price_cent")).intValue();
            int available="DRAFT".equals(session.get("state"))?capacity:((Number)row.get("available")).intValue();
            store.jdbc().update("UPDATE ticket_tier SET name=?,area_label=?,capacity=?,available=?,unit_price_cent=? WHERE id=?",name,area,capacity,available,price,tierId);
            return changed(number(session,"event_id"),sid,tierView(store.required("SELECT * FROM ticket_tier WHERE id=?",tierId)));
        });
    }
    public Map<String,Object> state(long user,long sessionId,String action,String raw) {
        auth.requireAdmin(user);body(raw,Set.of(),Set.of());
        if(!Set.of("publish","pause","resume").contains(action)) { throw invalid(); }
        return mutate(()-> {
            var row=required("SELECT * FROM event_session WHERE id=? FOR UPDATE",sessionId);
            String before=(String)row.get("state"),after=action.equals("pause")?"PAUSED":"OPEN";
            if(action.equals("publish")) {
                if(!before.equals("DRAFT")) { throw new ApiFailure(409,"SESSION_ALREADY_PUBLISHED"); }
                long valid=store.jdbc().queryForObject("SELECT COUNT(*) FROM ticket_tier WHERE session_id=? AND capacity>0 AND available=capacity AND unit_price_cent>0",Long.class,sessionId);
                if(valid==0) { throw new ApiFailure(409,"TICKET_TIER_REQUIRED"); }
            } else if(action.equals("pause")&&!before.equals("OPEN") || action.equals("resume")&&!before.equals("PAUSED")) {
                throw new ApiFailure(409,"SESSION_STATE_CONFLICT");
            }
            window(instant(row.get("booking_open_at")),instant(row.get("booking_close_at")),instant(row.get("start_at")),runtime.afterLocks(store.jdbc()));
            one(store.jdbc().update("UPDATE event_session SET state=? WHERE id=? AND state=?",after,sessionId,before));
            return changed(number(row,"event_id"),sessionId,sessionView(store.required("SELECT * FROM event_session WHERE id=?",sessionId)));
        });
    }
    private record Change(long outboxId,Map<String,Object> result) { }
    private Map<String,Object> mutate(Supplier<Change> work) {
        Change done=tx.execute(status->work.get());
        // The mutation and invalidation fact commit together. Failed deletion remains recoverable, not a lost edit.
        invalidator.deliver(done.outboxId()); return done.result();
    }
    private Change changed(long eventId,Long sessionId,Map<String,Object> result) {
        Instant now=runtime.afterLocks(store.jdbc());
        long mutation=store.insert("INSERT INTO catalogue_mutation(event_id,event_session_id,created_at) VALUES(?,?,?)",eventId,sessionId,sqlTime(now));
        Map<String,Object> payload=new LinkedHashMap<>();payload.put("eventId",Long.toString(eventId));payload.put("sessionId",sessionId==null?null:Long.toString(sessionId));
        String raw;
        try { raw=new ObjectMapper().writeValueAsString(payload); } catch(Exception impossible) { throw new IllegalStateException(impossible); }
        long outbox=store.insert("INSERT INTO outbox_event(event_type,aggregate_id,schema_version,payload,state,attempts,next_attempt_at,created_at,catalogue_mutation_id) VALUES('CATALOG_INVALIDATE',?,1,?,'READY',0,?,?,?)",mutation,raw,sqlTime(now),sqlTime(now),mutation);
        return new Change(outbox,result);
    }
    private Map<String,Object> required(String sql,Object... args) {
        var row=store.optional(sql,args);if(row==null) { throw new ApiFailure(404,"RESOURCE_NOT_FOUND"); }return row;
    }
    private static void draft(Map<String,Object> session) {
        if(!"DRAFT".equals(session.get("state"))) { throw new ApiFailure(409,"PUBLISHED_FIELDS_FROZEN"); }
    }
    private Map<String,Object> eventView(Map<String,Object> row) {
        Map<String,Object> result=new LinkedHashMap<>();result.put("eventId",Long.toString(number(row,"id")));
        result.put("title",row.get("title"));result.put("description",java.util.Objects.toString(row.get("description"),""));result.put("venueText",row.get("venue_text"));return result;
    }
    private Map<String,Object> sessionView(Map<String,Object> row) {
        Map<String,Object> result=new LinkedHashMap<>();result.put("sessionId",Long.toString(number(row,"id")));
        result.put("eventId",Long.toString(number(row,"event_id")));result.put("state",row.get("state"));
        result.put("startAt",time(row.get("start_at")));result.put("bookingOpenAt",time(row.get("booking_open_at")));result.put("bookingCloseAt",time(row.get("booking_close_at")));return result;
    }
    private Map<String,Object> tierView(Map<String,Object> row) {
        Map<String,Object> result=new LinkedHashMap<>();result.put("tierId",Long.toString(number(row,"id")));result.put("sessionId",Long.toString(number(row,"session_id")));
        result.put("name",row.get("name"));result.put("areaLabel",row.get("area_label"));result.put("unitPriceCent",row.get("unit_price_cent"));
        result.put("currency",row.get("currency"));result.put("referenceAvailable",row.get("available"));result.put("capacity",row.get("capacity"));return result;
    }
    public static Map<String,Object> page(List<?> items,int page,int size,boolean hasNext) {
        return Map.of("items",items,"page",page,"size",size,"hasNext",hasNext);
    }
}
