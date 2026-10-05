package com.ticketing.booking.service.impl;

import com.ticketing.booking.model.vo.OrderListItemVO;
import com.ticketing.booking.model.vo.PageVO;
import com.ticketing.booking.repository.OrderRepository;
import com.ticketing.booking.service.OrderCloseService;
import com.ticketing.booking.service.OrderListingService;
import java.util.ArrayList;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import static com.ticketing.booking.domain.Values.*;

@Service
@Profile("local-sim")
public class OrderListingServiceImpl implements OrderListingService {
    private final OrderRepository repository;private final OrderCloseService orders;
    public OrderListingServiceImpl(OrderRepository repository,OrderCloseService orders) { this.repository=repository;this.orders=orders; }
    @Override public PageVO<OrderListItemVO> list(long user,int page,int size) {
        var selected=repository.listOwned(user,size+1,(page-1)*size);var result=new ArrayList<OrderListItemVO>();
        for(var row:selected.stream().limit(size).toList()) {
            result.add(new OrderListItemVO(orders.query(user,row.id()),Long.toString(row.eventId()),row.title(),row.tierName(),time(row.startsAt())));
        }
        return new PageVO<>(result,page,size,selected.size()>size);
    }
}
