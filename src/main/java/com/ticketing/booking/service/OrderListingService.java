package com.ticketing.booking.service;

import com.ticketing.booking.catalogue.CatalogueService;
import com.ticketing.booking.persistence.BookingStore;
import java.util.ArrayList;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import static com.ticketing.booking.domain.Values.*;

@Service
@Profile("local-sim")
public class OrderListingService {
    private final BookingStore store; private final OrderCloseService orders;
    public OrderListingService(BookingStore store,OrderCloseService orders) { this.store=store;this.orders=orders; }
    public Map<String,Object> list(long user,int page,int size) {
        var selected=store.jdbc().queryForList("SELECT o.id,e.id event_id,e.title,s.start_at,t.name tier_name FROM booking_order o JOIN event_session s ON s.id=o.session_id JOIN event e ON e.id=s.event_id JOIN ticket_tier t ON t.id=o.tier_id WHERE o.user_id=? ORDER BY o.hold_created_at DESC,o.id DESC LIMIT ? OFFSET ?",user,size+1,(page-1)*size);
        var result=new ArrayList<Map<String,Object>>();
        for(var row:selected.stream().limit(size).toList()) {
            var order=orders.query(user,number(row,"id"));
            order.put("eventId",Long.toString(number(row,"event_id")));order.put("eventTitle",row.get("title"));
            order.put("tierName",row.get("tier_name"));order.put("startAt",time(row.get("start_at")));
            result.add(order);
        }
        return CatalogueService.page(result,page,size,selected.size()>size);
    }
}
