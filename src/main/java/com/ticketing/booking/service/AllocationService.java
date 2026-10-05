package com.ticketing.booking.service;

public interface AllocationService {
    void process(long requestId);
    long technicalFailures();
    String lastFailureType();
}
