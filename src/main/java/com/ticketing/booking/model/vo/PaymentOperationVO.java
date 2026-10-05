package com.ticketing.booking.model.vo;


/** Explicit VO contract for the simulated payment/refund flow. */
public record PaymentOperationVO(
    String operationId,
    String operatorId,
    String attemptId,
    String generation,
    String action,
    String targetId,
    String reason,
    long deliveries,
    String status,
    String result,
    String beforeSettlement,
    String beforeEvent,
    long beforeAttempts,
    String beforeError,
    String acceptedAt,
    String completedAt,
    String rejectionReason,
    String statusUrl,
    boolean simulated) implements ProcessingRetryResultVO {
    public PaymentOperationVO withStatusUrl(String statusUrl) {
        return new PaymentOperationVO(operationId,operatorId,attemptId,generation,action,targetId,reason,deliveries,status,result,beforeSettlement,beforeEvent,beforeAttempts,beforeError,acceptedAt,completedAt,rejectionReason,statusUrl,simulated);
    }
}
