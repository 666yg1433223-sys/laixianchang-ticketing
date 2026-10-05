package com.ticketing.booking.service;

import com.ticketing.booking.model.dto.ProcessingRetryDTO;
import com.ticketing.booking.model.vo.*;

public interface ProcessingRecoveryService {
    ProcessingFailuresVO list(long operator, int page, int size);
    ProcessingRetryResultVO retry(long operator, ProcessingRetryDTO input);
    PaymentOperationVO operation(long operator, String operationId);
}
