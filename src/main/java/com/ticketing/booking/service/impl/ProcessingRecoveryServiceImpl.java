package com.ticketing.booking.service.impl;

import com.ticketing.booking.validation.InputValidation;
import com.ticketing.booking.domain.ApiFailure;
import com.ticketing.booking.model.dto.ProcessingRetryDTO;
import com.ticketing.booking.model.vo.*;
import com.ticketing.booking.repository.ProcessingRecoveryRepository;
import com.ticketing.booking.security.PaymentManagementAccess.Operator;
import com.ticketing.booking.service.*;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.ticketing.booking.domain.Values.one;

/** Type-routed recovery with the same lock order and atomic retry audit as the original endpoint. */
@Service
@Profile("local-sim")
public class ProcessingRecoveryServiceImpl implements ProcessingRecoveryService {
    private final ProcessingRecoveryRepository repository;
    private final AuthService auth;
    private final PaymentManagementService payments;
    private final TransactionTemplate tx;

    public ProcessingRecoveryServiceImpl(ProcessingRecoveryRepository repository, AuthService auth,
            PaymentManagementService payments, PlatformTransactionManager manager) {
        this.repository = repository;
        this.auth = auth;
        this.payments = payments;
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(2);
        tx.setTimeout(8);
    }

    @Override
    public ProcessingFailuresVO list(long operator, int page, int size) {
        auth.requireAdmin(operator);
        int offset = InputValidation.page(page, size);
        var rows = repository.failedMessages(size + 1, offset);
        var items = rows.stream().limit(size).map(row -> new ProcessingFailureVO(Long.toString(row.id()),
            row.eventType(), Long.toString(row.aggregateId()), row.attempts(), row.lastError())).toList();
        return new ProcessingFailuresVO(items, page, size, rows.size() > size,
            repository.backlog(), repository.invalidMessageCount());
    }

    @Override
    public ProcessingRetryResultVO retry(long operator, ProcessingRetryDTO input) {
        auth.requireAdmin(operator);
        InputValidation.key(input.idempotencyKey());
        String reason = input.reason();
        if (reason == null || reason.isEmpty() || reason.length() > 200 || reason.chars().anyMatch(Character::isISOControl)) {
            throw new ApiFailure(400, "INVALID_ARGUMENT");
        }
        long id = input.eventId();
        var event = repository.findMessage(id);
        if (event == null) { throw new ApiFailure(404, "RESOURCE_NOT_FOUND"); }
        if ("PAYMENT_RESULT_RECORDED".equals(event.eventType())) {
            long attempt = event.paymentAttemptId();
            return localReceipt(payments.recover(new Operator(operator, Set.of(attempt)), attempt, input.idempotencyKey(), reason));
        }
        return tx.execute(status -> {
            Long request = null;
            if ("BOOKING_REQUESTED".equals(event.eventType())) {
                request = event.bookingRequestId();
                var route = repository.route(request);
                repository.lockSession(route.sessionId());
                repository.lockTier(route.tierId());
                repository.lockQuota(route.userId(), route.sessionId());
                repository.lockRequest(request);
            }
            var locked = repository.lockMessage(id);
            var previous = repository.previousRetry(operator, input.idempotencyKey());
            if (previous != null) {
                if (previous.eventId() != id || !reason.equals(previous.reason())) { throw new ApiFailure(409, "IDEMPOTENCY_CONFLICT"); }
                return new ProcessingRetryAcceptedVO(Long.toString(id), true);
            }
            if (!"DEAD".equals(locked.state())) { throw new ApiFailure(409, "NOT_RECOVERABLE"); }
            if (request != null) {
                if (repository.reopenRequest(request) != 1) { throw new ApiFailure(409, "PROCESS_DEADLINE_EXCEEDED"); }
            } else if (!"CATALOG_INVALIDATE".equals(locked.eventType())) { throw new ApiFailure(409, "NOT_RECOVERABLE"); }
            one(repository.reopenMessage(id));
            one(repository.recordRetry(id, operator, reason, input.idempotencyKey()));
            return new ProcessingRetryAcceptedVO(Long.toString(id), true);
        });
    }

    @Override
    public PaymentOperationVO operation(long operator, String operationId) {
        auth.requireAdmin(operator);
        if (operationId == null || !operationId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new ApiFailure(400, "INVALID_ARGUMENT");
        }
        Long attempt = repository.operationAttempt(operationId);
        if (attempt == null) { throw new ApiFailure(404, "RESOURCE_NOT_FOUND"); }
        return localReceipt(payments.query(new Operator(operator, Set.of(attempt)), operationId));
    }

    private PaymentOperationVO localReceipt(PaymentOperationVO receipt) {
        return receipt.withStatusUrl("/v1/admin/processing-failures/operations/" + receipt.operationId());
    }
}
