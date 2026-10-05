package com.ticketing.booking.service;

import com.ticketing.booking.model.dto.RefundAcceptDTO;
import com.ticketing.booking.model.dto.RefundRetryDTO;
import com.ticketing.booking.model.vo.RefundAcceptedVO;
import com.ticketing.booking.model.vo.RefundVO;
import com.ticketing.booking.model.vo.PageVO;

/** Full simulated refunds and generation-fenced technical recovery. */
public interface RefundService {
    boolean enabled();
    RefundAcceptedVO accept(RefundAcceptDTO request);
    default RefundAcceptedVO accept(long user,long order,String key,String reason) { return accept(new RefundAcceptDTO(user,order,key,reason)); }
    RefundVO query(long user,long id);
    RefundVO queryForAdmin(long id);
    PageVO<RefundVO> listForAdmin(String status,int page,int size);
    void settle(long id);
    void scanOnce();
    RefundAcceptedVO retry(RefundRetryDTO request);
    default RefundAcceptedVO retry(long operator,long id,String key,String reason) { return retry(new RefundRetryDTO(operator,id,key,reason)); }
}
