-- Forward only; multi-statement DDL is not transactionally atomic. Never repair on failure.
ALTER TABLE booking_order
  ADD COLUMN current_attempt_id BIGINT NULL,
  ADD COLUMN paid_attempt_id BIGINT NULL,
  ADD COLUMN paid_at DATETIME(3) NULL,
  ADD UNIQUE KEY uq_order_paid_reverse (paid_attempt_id),
  DROP CHECK ck_order_close_state,
  ADD CONSTRAINT ck_order_close_state CHECK (
    (status='HOLD' AND closed_at IS NULL AND close_reason IS NULL) OR
    (status='PAID' AND closed_at IS NULL AND close_reason IS NULL) OR
    (status='CANCELLED' AND closed_at IS NOT NULL AND close_reason IS NOT NULL
      AND close_reason='USER_CANCELLED' AND closed_at>=hold_created_at AND closed_at<pay_expires_at) OR
    (status='EXPIRED' AND closed_at IS NOT NULL AND close_reason IS NOT NULL
      AND close_reason='PAYMENT_DEADLINE_EXCEEDED' AND closed_at>=pay_expires_at)
  ),
  ADD CONSTRAINT ck_order_payment CHECK (
    (status='PAID' AND current_attempt_id IS NOT NULL AND paid_attempt_id IS NOT NULL
      AND current_attempt_id=paid_attempt_id AND paid_at IS NOT NULL
      AND paid_at>=hold_created_at AND paid_at<pay_expires_at) OR
    (status<>'PAID' AND paid_attempt_id IS NULL AND paid_at IS NULL)
  );

CREATE TABLE payment_attempt (
  id BIGINT NOT NULL AUTO_INCREMENT,
  order_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  session_id BIGINT NOT NULL,
  idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  attempt_no INT NOT NULL,
  amount_cent INT NOT NULL,
  currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME(3) NOT NULL,
  planned_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  planned_at DATETIME(3) NOT NULL,
  resolution_deadline DATETIME(3) NOT NULL,
  result_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
  result_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  result_at DATETIME(3) NULL,
  result_reason VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NULL,
  result_digest BINARY(32) NULL,
  settlement_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'UNRESOLVED',
  settled_at DATETIME(3) NULL,
  review_reason VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  compensation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  compensation_cent INT NULL,
  compensation_at DATETIME(3) NULL,
  applied_order_id BIGINT GENERATED ALWAYS AS (CASE WHEN settlement_status='APPLIED' THEN order_id ELSE NULL END) STORED,
  PRIMARY KEY(id),
  CONSTRAINT uq_payment_idempotency UNIQUE(user_id,idempotency_key),
  CONSTRAINT uq_payment_sequence UNIQUE(order_id,attempt_no),
  CONSTRAINT uq_payment_result UNIQUE(result_id),
  CONSTRAINT uq_payment_compensation UNIQUE(compensation_id),
  CONSTRAINT uq_payment_applied UNIQUE(applied_order_id),
  KEY ix_payment_owner(order_id,user_id,session_id),
  KEY ix_payment_generation(result_status,planned_at,id),
  CONSTRAINT fk_payment_order FOREIGN KEY(order_id,user_id,session_id)
    REFERENCES booking_order(id,user_id,session_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_payment_plan CHECK(attempt_no>0 AND amount_cent>0 AND currency='CNY'
    AND planned_status IN ('SUCCESS','FAILED') AND planned_at>=created_at
    AND planned_at<=created_at+INTERVAL 20 MINUTE AND resolution_deadline=created_at+INTERVAL 30 MINUTE),
  CONSTRAINT ck_payment_result CHECK(
    (result_status='PENDING' AND result_id IS NULL AND result_at IS NULL AND result_reason IS NULL AND result_digest IS NULL) OR
    (result_status IN ('SUCCESS','FAILED') AND result_id IS NOT NULL AND result_at IS NOT NULL
      AND result_reason IS NOT NULL AND result_digest IS NOT NULL AND result_at>=created_at)
  ),
  CONSTRAINT ck_payment_settlement CHECK(
    (settlement_status='UNRESOLVED' AND settled_at IS NULL AND review_reason IS NULL) OR
    (settlement_status='APPLIED' AND result_status='SUCCESS' AND settled_at IS NOT NULL AND review_reason IS NULL) OR
    (settlement_status='NO_CHARGE' AND result_status='FAILED' AND settled_at IS NOT NULL AND review_reason IS NULL) OR
    (settlement_status='REVERSED' AND result_status='SUCCESS' AND settled_at IS NOT NULL AND review_reason IS NULL) OR
    (settlement_status='REVIEW' AND result_status IN ('SUCCESS','FAILED') AND settled_at IS NULL AND review_reason IS NOT NULL)
  ),
  CONSTRAINT ck_payment_pending CHECK(result_status<>'PENDING' OR settlement_status='UNRESOLVED'),
  CONSTRAINT ck_payment_compensation CHECK(
    (settlement_status='REVERSED' AND compensation_id IS NOT NULL AND compensation_cent IS NOT NULL
      AND compensation_cent=amount_cent AND compensation_at IS NOT NULL AND compensation_at=settled_at) OR
    (settlement_status<>'REVERSED' AND compensation_id IS NULL AND compensation_cent IS NULL AND compensation_at IS NULL)
  ),
  CONSTRAINT ck_payment_settled_time CHECK(settled_at IS NULL OR (result_at IS NOT NULL AND settled_at>=result_at))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE outbox_event ADD COLUMN booking_request_id BIGINT NULL, ADD COLUMN payment_attempt_id BIGINT NULL;
UPDATE outbox_event SET booking_request_id=aggregate_id WHERE event_type='BOOKING_REQUESTED';
ALTER TABLE outbox_event
  ADD CONSTRAINT fk_outbox_booking_source FOREIGN KEY(booking_request_id) REFERENCES booking_request(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT fk_outbox_payment_source FOREIGN KEY(payment_attempt_id) REFERENCES payment_attempt(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT ck_outbox_typed_source CHECK(schema_version=1 AND (
    (event_type='BOOKING_REQUESTED' AND booking_request_id IS NOT NULL AND booking_request_id=aggregate_id AND payment_attempt_id IS NULL) OR
    (event_type='PAYMENT_RESULT_RECORDED' AND payment_attempt_id IS NOT NULL AND payment_attempt_id=aggregate_id AND booking_request_id IS NULL)
  ));
ALTER TABLE outbox_event DROP FOREIGN KEY fk_outbox_request, DROP CHECK ck_outbox_kind;
