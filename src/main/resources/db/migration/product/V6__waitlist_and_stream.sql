-- IF NOT EXISTS permits resuming the known empty first statement after the FK-name failure.
CREATE TABLE IF NOT EXISTS waitlist_entry (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  session_id BIGINT NOT NULL,
  tier_id BIGINT NOT NULL,
  idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payload_hash BINARY(32) NOT NULL,
  status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'WAITING',
  order_id BIGINT NULL,
  created_at DATETIME(3) NOT NULL,
  finished_at DATETIME(3) NULL,
  CONSTRAINT uq_waitlist_key UNIQUE(user_id,idempotency_key),
  CONSTRAINT uq_waitlist_identity UNIQUE(id,user_id,session_id,tier_id),
  CONSTRAINT uq_waitlist_order UNIQUE(order_id),
  KEY ix_waitlist_head(tier_id,status,id),
  KEY ix_waitlist_user(user_id,id),
  CONSTRAINT fk_waitlist_user FOREIGN KEY(user_id) REFERENCES app_user(id) ON DELETE RESTRICT,
  CONSTRAINT fk_waitlist_tier FOREIGN KEY(tier_id,session_id) REFERENCES ticket_tier(id,session_id) ON DELETE RESTRICT,
  CONSTRAINT ck_waitlist_state CHECK(
    (status='WAITING' AND order_id IS NULL AND finished_at IS NULL) OR
    (status='ALLOCATED' AND order_id IS NOT NULL AND finished_at IS NOT NULL) OR
    (status IN ('CANCELLED','EXPIRED') AND order_id IS NULL AND finished_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
-- The source is exactly one of request and waitlist. Keep both composite association constraints.
ALTER TABLE booking_order
  DROP FOREIGN KEY fk_order_request,
  MODIFY request_id BIGINT NULL,
  ADD COLUMN waitlist_entry_id BIGINT NULL,
  ADD CONSTRAINT uq_order_waitlist UNIQUE(waitlist_entry_id),
  ADD CONSTRAINT fk_order_request_v6 FOREIGN KEY(request_id,user_id,session_id,tier_id) REFERENCES booking_request(id,user_id,session_id,tier_id) ON DELETE RESTRICT,
  ADD CONSTRAINT fk_order_waitlist FOREIGN KEY(waitlist_entry_id,user_id,session_id,tier_id) REFERENCES waitlist_entry(id,user_id,session_id,tier_id) ON DELETE RESTRICT,
  ADD CONSTRAINT ck_order_one_source CHECK((request_id IS NOT NULL AND waitlist_entry_id IS NULL) OR (request_id IS NULL AND waitlist_entry_id IS NOT NULL));
-- Polymorphic owner_id retains its API meaning; generated typed columns restore FK protection.
ALTER TABLE user_session_quota
  DROP FOREIGN KEY fk_quota_order,
  DROP CHECK ck_quota_owner,
  ADD COLUMN order_owner_id BIGINT GENERATED ALWAYS AS (CASE WHEN owner_type='ORDER' THEN owner_id ELSE NULL END) STORED,
  ADD COLUMN waitlist_owner_id BIGINT GENERATED ALWAYS AS (CASE WHEN owner_type='WAITLIST' THEN owner_id ELSE NULL END) STORED,
  ADD CONSTRAINT fk_quota_typed_order FOREIGN KEY(order_owner_id,user_id,session_id) REFERENCES booking_order(id,user_id,session_id) ON DELETE RESTRICT,
  ADD CONSTRAINT fk_quota_waitlist FOREIGN KEY(waitlist_owner_id) REFERENCES waitlist_entry(id) ON DELETE RESTRICT,
  ADD CONSTRAINT ck_quota_owner CHECK((owner_type='NONE' AND owner_id IS NULL) OR (owner_type IN ('ORDER','WAITLIST') AND owner_id IS NOT NULL));
ALTER TABLE booking_request DROP CHECK ck_request_state,
  ADD CONSTRAINT ck_request_state CHECK(
    (status='ACCEPTED' AND reason_code IS NULL AND order_id IS NULL AND finished_at IS NULL) OR
    (status='SUCCEEDED' AND reason_code IS NULL AND order_id IS NOT NULL AND finished_at IS NOT NULL) OR
    (status='REJECTED' AND reason_code IN ('NO_TICKETS','QUOTA_ALREADY_HELD','SESSION_NOT_OPEN','WAITLIST_REQUIRED') AND reason_code IS NOT NULL AND order_id IS NULL AND finished_at IS NOT NULL) OR
    (status='EXPIRED' AND reason_code='PROCESS_DEADLINE_EXCEEDED' AND order_id IS NULL AND finished_at IS NOT NULL) OR
    (status='PROCESSING_FAILED' AND reason_code='RETRY_EXHAUSTED' AND order_id IS NULL AND finished_at IS NOT NULL)
  );
ALTER TABLE outbox_event
  ADD COLUMN lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD COLUMN lease_until DATETIME(3) NULL,
  ADD COLUMN published_at DATETIME(3) NULL,
  DROP CHECK ck_outbox_recovery_generation,
  ADD CONSTRAINT ck_outbox_recovery_generation CHECK(recovery_generation>=0),
  DROP CHECK ck_outbox_state,
  ADD CONSTRAINT ck_outbox_state CHECK(
    (state IN ('READY','PUBLISHED') AND next_attempt_at IS NOT NULL AND finished_at IS NULL) OR
    (state IN ('DONE','DEAD') AND next_attempt_at IS NULL AND finished_at IS NOT NULL)
  );
CREATE TABLE invalid_stream_message (
  message_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  reason VARCHAR(64) NOT NULL,
  summary VARCHAR(200) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE processing_retry (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  event_id BIGINT NOT NULL,
  operator_id BIGINT NOT NULL,
  reason VARCHAR(200) NOT NULL,
  idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  CONSTRAINT uq_processing_retry UNIQUE(operator_id,idempotency_key),
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_retry_event FOREIGN KEY(event_id) REFERENCES outbox_event(id) ON DELETE RESTRICT,
  CONSTRAINT fk_retry_user FOREIGN KEY(operator_id) REFERENCES app_user(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
