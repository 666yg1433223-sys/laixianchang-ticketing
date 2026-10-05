package com.ticketing.booking.service;

import com.ticketing.booking.model.vo.OrderListItemVO;
import com.ticketing.booking.model.vo.PageVO;

public interface OrderListingService {
    PageVO<OrderListItemVO> list(long user,int page,int size);
}
