package com.ticketing.booking.service;

import com.ticketing.booking.model.dto.CreateBookingDTO;
import com.ticketing.booking.model.vo.BookingAcceptedVO;
import com.ticketing.booking.model.vo.BookingStatusVO;

public interface BookingService {
    BookingAcceptedVO accept(CreateBookingDTO request);
    BookingStatusVO query(long user,long request);
    default BookingAcceptedVO accept(long user,long session,long tier,String key) {
        return accept(new CreateBookingDTO(user,session,tier,key));
    }
}
