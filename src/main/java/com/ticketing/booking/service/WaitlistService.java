package com.ticketing.booking.service;

import com.ticketing.booking.model.dto.JoinWaitlistDTO;
import com.ticketing.booking.model.vo.PageVO;
import com.ticketing.booking.model.vo.WaitlistVO;

public interface WaitlistService {
    WaitlistVO join(JoinWaitlistDTO request);
    WaitlistVO query(long user,long id);
    PageVO<WaitlistVO> list(long user,int page,int size);
    WaitlistVO cancel(long user,long id);
    void advance(long tier);
    default WaitlistVO join(long user,long session,long tier,String key) { return join(new JoinWaitlistDTO(user,session,tier,key)); }
}
