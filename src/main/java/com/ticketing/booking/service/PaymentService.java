package com.ticketing.booking.service;

import com.ticketing.booking.model.dto.PaymentAcceptDTO;
import com.ticketing.booking.model.po.PaymentAttemptPO;
import com.ticketing.booking.model.po.PaymentResultEventPO;
import com.ticketing.booking.model.vo.PaymentAcceptedVO;
import com.ticketing.booking.model.vo.PaymentVO;

/** Simulated payment acceptance, immutable result generation and atomic settlement. */
public interface PaymentService {
    boolean enabled();
    PaymentAcceptedVO accept(PaymentAcceptDTO request);
    default PaymentAcceptedVO accept(long user,long order,String key) { return accept(new PaymentAcceptDTO(user,order,key)); }
    PaymentVO query(long user,long id);
    void generate(long id);
    void settle(long id);
    /** Internal recovery collaboration: the caller must own a transaction. */
    OrderCloseService.LockedOrder lockRoute(long attempt);
    void lockOperations(long attempt,long generation);
    void validateTrusted(OrderCloseService.LockedOrder locked,PaymentAttemptPO attempt,PaymentResultEventPO event,boolean allowReview);
}
