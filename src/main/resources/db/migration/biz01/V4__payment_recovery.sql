-- Forward-only: an explicit recovery round never rewrites an immutable payment result.
ALTER TABLE outbox_event
  ADD COLUMN recovery_generation BIGINT NOT NULL DEFAULT 0,
  ADD CONSTRAINT ck_outbox_recovery_generation CHECK (
    recovery_generation >= 0 AND (event_type='PAYMENT_RESULT_RECORDED' OR recovery_generation=0)
  );

CREATE TABLE sim_payment_operation (
  operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  operator_id BIGINT NOT NULL,
  action VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  attempt_id BIGINT NOT NULL,
  target_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  reason VARCHAR(200) NOT NULL,
  deliveries SMALLINT NOT NULL,
  parameter_digest BINARY(32) NOT NULL,
  before_settlement VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  before_event VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  before_attempts SMALLINT NOT NULL,
  before_error VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  generation BIGINT NOT NULL,
  accepted_at DATETIME(3) NULL,
  completed_at DATETIME(3) NULL,
  status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  result VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
  rejection_reason VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  PRIMARY KEY(operation_id),
  CONSTRAINT uq_sim_operation_idempotency UNIQUE(operator_id,idempotency_key),
  KEY ix_sim_operation_attempt(attempt_id,generation,status),
  CONSTRAINT fk_sim_operation_attempt FOREIGN KEY(attempt_id) REFERENCES payment_attempt(id)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_sim_operation_input CHECK(operator_id>0 AND generation>=0
    AND CHAR_LENGTH(reason) BETWEEN 1 AND 200 AND before_attempts BETWEEN 0 AND 6
    AND ((action='RECOVER' AND deliveries=1) OR (action='REDELIVER' AND deliveries BETWEEN 1 AND 3))),
  CONSTRAINT ck_sim_operation_state CHECK(
    (status='ACCEPTED' AND accepted_at IS NOT NULL AND completed_at IS NULL AND result IS NULL AND rejection_reason IS NULL) OR
    (status='COMPLETED' AND accepted_at IS NOT NULL AND completed_at IS NOT NULL
      AND completed_at>=accepted_at AND result IS NOT NULL AND result IN ('APPLIED','NO_CHARGE','REVERSED','REVIEW') AND rejection_reason IS NULL) OR
    (status='REJECTED' AND accepted_at IS NULL AND completed_at IS NOT NULL AND result IS NULL AND rejection_reason IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
