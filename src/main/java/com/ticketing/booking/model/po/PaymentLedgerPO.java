package com.ticketing.booking.model.po;


/** Explicit PO contract for the simulated payment/refund flow. */
public record PaymentLedgerPO(
    String action,
    int delta,
    long tierId) {

}
