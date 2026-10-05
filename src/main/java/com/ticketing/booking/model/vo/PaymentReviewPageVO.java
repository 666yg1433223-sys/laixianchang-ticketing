package com.ticketing.booking.model.vo;

import java.util.List;

/** Explicit VO contract for the simulated payment/refund flow. */
public record PaymentReviewPageVO(
    List<PaymentReviewVO> items,
    String nextAfterAttemptId) {

}
