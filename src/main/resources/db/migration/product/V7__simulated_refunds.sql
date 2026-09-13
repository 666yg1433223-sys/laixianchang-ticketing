-- Additive local product migration. Original payment SUCCESS/APPLIED facts are never rewritten.
ALTER TABLE payment_attempt
  ADD CONSTRAINT uq_payment_refund_identity UNIQUE(id,order_id,user_id,session_id);

CREATE TABLE refund_request (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  order_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  session_id BIGINT NOT NULL,
  payment_attempt_id BIGINT NOT NULL,
  idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  amount_cent INT NOT NULL,
  currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  reason VARCHAR(200) NOT NULL,
  status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
  requested_at DATETIME(3) NOT NULL,
  completed_at DATETIME(3) NULL,
  next_attempt_at DATETIME(3) NULL,
  attempts SMALLINT NOT NULL DEFAULT 0,
  recovery_generation BIGINT NOT NULL DEFAULT 0,
  review_reason VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  last_error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  CONSTRAINT uq_refund_order UNIQUE(order_id),
  CONSTRAINT uq_refund_payment UNIQUE(payment_attempt_id),
  CONSTRAINT uq_refund_key UNIQUE(user_id,idempotency_key),
  CONSTRAINT uq_refund_identity UNIQUE(id,order_id,user_id,session_id),
  KEY ix_refund_due(status,next_attempt_at,id),
  CONSTRAINT fk_refund_order FOREIGN KEY(order_id,user_id,session_id)
    REFERENCES booking_order(id,user_id,session_id) ON DELETE RESTRICT,
  CONSTRAINT fk_refund_payment FOREIGN KEY(payment_attempt_id,order_id,user_id,session_id)
    REFERENCES payment_attempt(id,order_id,user_id,session_id) ON DELETE RESTRICT,
  CONSTRAINT ck_refund_input CHECK(amount_cent>0 AND currency='CNY' AND CHAR_LENGTH(TRIM(reason)) BETWEEN 1 AND 200),
  CONSTRAINT ck_refund_attempts CHECK(attempts BETWEEN 0 AND 6 AND recovery_generation>=0),
  CONSTRAINT ck_refund_state CHECK(
    (status='PENDING' AND completed_at IS NULL AND next_attempt_at IS NOT NULL AND review_reason IS NULL AND attempts<6) OR
    (status='SUCCEEDED' AND completed_at IS NOT NULL AND completed_at>=requested_at AND next_attempt_at IS NULL AND review_reason IS NULL) OR
    (status='REVIEW' AND completed_at IS NULL AND next_attempt_at IS NULL AND review_reason IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE booking_order
  ADD COLUMN current_refund_id BIGINT NULL,
  ADD COLUMN refunded_at DATETIME(3) NULL,
  ADD CONSTRAINT uq_order_current_refund UNIQUE(current_refund_id),
  ADD CONSTRAINT fk_order_current_refund FOREIGN KEY(current_refund_id,id,user_id,session_id)
    REFERENCES refund_request(id,order_id,user_id,session_id) ON DELETE RESTRICT,
  DROP CHECK ck_order_close_state,
  DROP CHECK ck_order_payment,
  ADD CONSTRAINT ck_order_close_state CHECK(
    (status IN ('HOLD','PAID','REFUNDING') AND closed_at IS NULL AND close_reason IS NULL) OR
    (status='CANCELLED' AND closed_at IS NOT NULL AND close_reason IS NOT NULL
      AND close_reason='USER_CANCELLED' AND closed_at>=hold_created_at AND closed_at<pay_expires_at) OR
    (status='EXPIRED' AND closed_at IS NOT NULL AND close_reason IS NOT NULL
      AND close_reason='PAYMENT_DEADLINE_EXCEEDED' AND closed_at>=pay_expires_at) OR
    (status='REFUNDED' AND closed_at IS NOT NULL AND close_reason IS NOT NULL
      AND close_reason='USER_REFUNDED' AND refunded_at IS NOT NULL AND closed_at=refunded_at AND refunded_at>=paid_at)
  ),
  ADD CONSTRAINT ck_order_payment CHECK(
    (status IN ('PAID','REFUNDING','REFUNDED') AND current_attempt_id IS NOT NULL AND paid_attempt_id IS NOT NULL
      AND current_attempt_id=paid_attempt_id AND paid_at IS NOT NULL
      AND paid_at>=hold_created_at AND paid_at<pay_expires_at) OR
    (status NOT IN ('PAID','REFUNDING','REFUNDED') AND paid_attempt_id IS NULL AND paid_at IS NULL)
  ),
  ADD CONSTRAINT ck_order_refund CHECK(
    (status='REFUNDING' AND current_refund_id IS NOT NULL AND refunded_at IS NULL) OR
    (status='REFUNDED' AND current_refund_id IS NOT NULL AND refunded_at IS NOT NULL) OR
    (status NOT IN ('REFUNDING','REFUNDED') AND current_refund_id IS NULL AND refunded_at IS NULL)
  );

CREATE TABLE refund_retry (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  refund_id BIGINT NOT NULL,
  operator_id BIGINT NOT NULL,
  idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  reason VARCHAR(200) NOT NULL,
  generation BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL,
  CONSTRAINT uq_refund_retry_key UNIQUE(operator_id,idempotency_key),
  CONSTRAINT uq_refund_retry_generation UNIQUE(refund_id,generation),
  CONSTRAINT fk_refund_retry_refund FOREIGN KEY(refund_id) REFERENCES refund_request(id) ON DELETE RESTRICT,
  CONSTRAINT fk_refund_retry_operator FOREIGN KEY(operator_id) REFERENCES app_user(id) ON DELETE RESTRICT,
  CONSTRAINT ck_refund_retry_input CHECK(generation>0 AND CHAR_LENGTH(TRIM(reason)) BETWEEN 1 AND 200)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
