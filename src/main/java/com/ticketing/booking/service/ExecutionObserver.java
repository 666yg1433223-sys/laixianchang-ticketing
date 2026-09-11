package com.ticketing.booking.service;

/** Transaction boundary observer; the only active implementation lives on the test classpath. */
public interface ExecutionObserver {
    void at(String boundary, long requestId);
    boolean directEnabled();
    boolean recoveryEnabled();
    default void redisKeys(java.util.List<String> keys) { }
}
