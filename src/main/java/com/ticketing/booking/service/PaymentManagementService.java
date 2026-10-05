package com.ticketing.booking.service;

import com.ticketing.booking.security.PaymentManagementAccess.Operator;
import com.ticketing.booking.model.dto.PaymentRecoveryDTO;
import com.ticketing.booking.model.dto.PaymentRedeliveryDTO;
import com.ticketing.booking.model.vo.PaymentOperationVO;
import com.ticketing.booking.model.vo.PaymentReviewPageVO;

/** Scoped simulated-payment recovery with durable, idempotent operation receipts. */
public interface PaymentManagementService {
    PaymentReviewPageVO list(Operator actor,long after,int limit);
    PaymentOperationVO recover(Operator actor,PaymentRecoveryDTO request);
    default PaymentOperationVO recover(Operator actor,long attempt,String key,String reason) {
        return recover(actor,new PaymentRecoveryDTO(attempt,key,reason));
    }
    PaymentOperationVO redeliver(Operator actor,PaymentRedeliveryDTO request);
    default PaymentOperationVO redeliver(Operator actor,String resultId,String key,String reason,int deliveries) {
        return redeliver(actor,new PaymentRedeliveryDTO(resultId,key,reason,deliveries));
    }
    PaymentOperationVO query(Operator actor,String operationId);
}
