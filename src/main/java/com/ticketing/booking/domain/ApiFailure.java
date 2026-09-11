package com.ticketing.booking.domain;

public final class ApiFailure extends RuntimeException {
    private final int status;
    private final String code;
    private final int retryAfter;
    public ApiFailure(int status, String code) { this(status, code, 0); }
    public ApiFailure(int status, String code, int retryAfter) {
        super(code); this.status = status; this.code = code; this.retryAfter = retryAfter;
    }
    public int status() { return status; }
    public String code() { return code; }
    public int retryAfter() { return retryAfter; }
    public ApiFailure causedBy(Throwable cause) { initCause(cause); return this; }
}
