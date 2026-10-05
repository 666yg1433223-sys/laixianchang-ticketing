package com.ticketing.booking.model.po;


/** Explicit PO contract for the simulated payment/refund flow. */
public record RefundQueryPO(
    RefundRequestPO refund,
    String orderStatus) {

}
