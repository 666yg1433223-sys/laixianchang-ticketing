-- BIZ-01-I-001 / MySQL 8.4 / authorized migration for isolated biz01_it only.
-- Proposed next migration: db/migration/biz01/V1__booking_slice.sql.
-- External provisioning selects ticketing_biz01 or ticketing_biz01_it; no USE/CREATE DATABASE/GRANT here.
-- Every connection must use UTC. DATETIME(3) <-> Java Instant <-> API UTC ISO8601 with 3 fractional digits.
-- IDs: signed BIGINT AUTO_INCREMENT; API positive decimal strings, Java Long range.
-- InnoDB, explicit READ COMMITTED transactions, no cascading history deletion.

CREATE TABLE app_user (
  id BIGINT NOT NULL AUTO_INCREMENT,
  subject_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  nickname VARCHAR(64) NOT NULL DEFAULT '',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT uq_user_subject UNIQUE (subject_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE event (
  id BIGINT NOT NULL AUTO_INCREMENT,
  title VARCHAR(128) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE event_session (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_id BIGINT NOT NULL,
  start_at DATETIME(3) NOT NULL,
  booking_open_at DATETIME(3) NOT NULL,
  booking_close_at DATETIME(3) NOT NULL,
  state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'DRAFT',
  PRIMARY KEY (id),
  KEY ix_session_event (event_id),
  CONSTRAINT fk_session_event FOREIGN KEY (event_id) REFERENCES event(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_session_state CHECK (state IN ('DRAFT','OPEN','PAUSED')),
  CONSTRAINT ck_session_window CHECK (booking_open_at < booking_close_at AND booking_close_at < start_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ticket_tier (
  id BIGINT NOT NULL AUTO_INCREMENT,
  session_id BIGINT NOT NULL,
  name VARCHAR(64) NOT NULL,
  capacity INT NOT NULL,
  available INT NOT NULL,
  unit_price_cent INT NOT NULL,
  currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'CNY',
  PRIMARY KEY (id),
  CONSTRAINT uq_tier_session UNIQUE (id,session_id),
  KEY ix_tier_session (session_id),
  CONSTRAINT fk_tier_session FOREIGN KEY (session_id) REFERENCES event_session(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_tier_stock CHECK (capacity >= 0 AND available >= 0 AND available <= capacity),
  CONSTRAINT ck_tier_money CHECK (unit_price_cent > 0 AND currency = 'CNY')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE booking_request (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  session_id BIGINT NOT NULL,
  tier_id BIGINT NOT NULL,
  quantity TINYINT NOT NULL DEFAULT 1,
  idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payload_hash BINARY(32) NOT NULL,
  status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACCEPTED',
  reason_code VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NULL,
  order_id BIGINT NULL COMMENT 'Reverse reference maintained in T2; forward order->request FK avoids circular insertion',
  accepted_at DATETIME(3) NOT NULL,
  process_deadline DATETIME(3) NOT NULL,
  finished_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  CONSTRAINT uq_request_idempotency UNIQUE (user_id,idempotency_key),
  CONSTRAINT uq_request_identity UNIQUE (id,user_id,session_id,tier_id),
  CONSTRAINT uq_request_order_reverse UNIQUE (order_id),
  KEY ix_request_tier_session (tier_id,session_id),
  KEY ix_request_deadline (status,process_deadline,id),
  CONSTRAINT fk_request_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_request_tier FOREIGN KEY (tier_id,session_id) REFERENCES ticket_tier(id,session_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_request_quantity CHECK (quantity = 1),
  CONSTRAINT ck_request_deadline CHECK (process_deadline > accepted_at),
  CONSTRAINT ck_request_state CHECK (
    (status = 'ACCEPTED' AND reason_code IS NULL AND order_id IS NULL AND finished_at IS NULL) OR
    (status = 'SUCCEEDED' AND reason_code IS NULL AND order_id IS NOT NULL AND finished_at IS NOT NULL) OR
    (status = 'REJECTED' AND reason_code IS NOT NULL AND reason_code IN ('NO_TICKETS','QUOTA_ALREADY_HELD','SESSION_NOT_OPEN') AND order_id IS NULL AND finished_at IS NOT NULL) OR
    (status = 'EXPIRED' AND reason_code = 'PROCESS_DEADLINE_EXCEEDED' AND order_id IS NULL AND finished_at IS NOT NULL) OR
    (status = 'PROCESSING_FAILED' AND reason_code = 'RETRY_EXHAUSTED' AND order_id IS NULL AND finished_at IS NOT NULL)
  ),
  CONSTRAINT ck_request_failure_reason CHECK (status IN ('ACCEPTED','SUCCEEDED') OR reason_code IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE booking_order (
  id BIGINT NOT NULL AUTO_INCREMENT,
  request_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  session_id BIGINT NOT NULL,
  tier_id BIGINT NOT NULL,
  status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'HOLD',
  unit_price_cent INT NOT NULL,
  quantity TINYINT NOT NULL DEFAULT 1,
  payable_cent INT NOT NULL,
  currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'CNY',
  hold_created_at DATETIME(3) NOT NULL,
  pay_expires_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uq_order_request UNIQUE (request_id),
  CONSTRAINT uq_order_owner_identity UNIQUE (id,user_id,session_id),
  CONSTRAINT uq_order_tier_identity UNIQUE (id,tier_id),
  KEY ix_order_request_identity (request_id,user_id,session_id,tier_id),
  KEY ix_order_user_created (user_id,hold_created_at,id),
  KEY ix_order_expiry (status,pay_expires_at,id),
  CONSTRAINT fk_order_request FOREIGN KEY (request_id,user_id,session_id,tier_id) REFERENCES booking_request(id,user_id,session_id,tier_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_order_hold_only CHECK (status = 'HOLD'),
  CONSTRAINT ck_order_snapshot CHECK (quantity = 1 AND unit_price_cent > 0 AND payable_cent = unit_price_cent AND currency = 'CNY'),
  CONSTRAINT ck_order_deadline CHECK (pay_expires_at > hold_created_at AND pay_expires_at <= hold_created_at + INTERVAL 10 MINUTE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE user_session_quota (
  user_id BIGINT NOT NULL,
  session_id BIGINT NOT NULL,
  owner_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'NONE',
  owner_id BIGINT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (user_id,session_id),
  KEY ix_quota_session (session_id),
  KEY ix_quota_owner (owner_id,user_id,session_id),
  CONSTRAINT fk_quota_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_quota_session FOREIGN KEY (session_id) REFERENCES event_session(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_quota_order FOREIGN KEY (owner_id,user_id,session_id) REFERENCES booking_order(id,user_id,session_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_quota_owner CHECK ((owner_type = 'NONE' AND owner_id IS NULL) OR (owner_type = 'ORDER' AND owner_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE inventory_ledger (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tier_id BIGINT NOT NULL,
  order_id BIGINT NOT NULL,
  action VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  delta INT NOT NULL,
  available_after INT NOT NULL,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uq_ledger_order_action UNIQUE (order_id,action),
  KEY ix_ledger_order_tier (order_id,tier_id),
  KEY ix_ledger_tier (tier_id,id),
  CONSTRAINT fk_ledger_order FOREIGN KEY (order_id,tier_id) REFERENCES booking_order(id,tier_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_ledger_reserve CHECK (action = 'RESERVE' AND delta = -1 AND available_after >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE outbox_event (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  aggregate_id BIGINT NOT NULL COMMENT 'booking_request.id for this slice',
  schema_version SMALLINT NOT NULL DEFAULT 1,
  payload JSON NOT NULL,
  state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'READY',
  attempts SMALLINT NOT NULL DEFAULT 0 COMMENT 'Persisted technical failure count, max 6 including the initial attempt',
  next_attempt_at DATETIME(3) NULL,
  last_error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  created_at DATETIME(3) NOT NULL,
  finished_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  CONSTRAINT uq_outbox_source UNIQUE (event_type,aggregate_id),
  KEY ix_outbox_request (aggregate_id),
  KEY ix_outbox_due (state,next_attempt_at,id),
  CONSTRAINT fk_outbox_request FOREIGN KEY (aggregate_id) REFERENCES booking_request(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_outbox_kind CHECK (event_type = 'BOOKING_REQUESTED' AND schema_version = 1),
  CONSTRAINT ck_outbox_attempts CHECK (attempts >= 0 AND attempts <= 6),
  CONSTRAINT ck_outbox_state CHECK (
    (state = 'READY' AND next_attempt_at IS NOT NULL AND finished_at IS NULL) OR
    (state IN ('DONE','DEAD') AND next_attempt_at IS NULL AND finished_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- No payment_attempt, waitlist, RELEASE, PAID mutation, seed INSERT, cleanup or triggers in this draft.
-- Next close/payment slices must forward-migrate ck_order_hold_only, ck_ledger_reserve,
-- add close/paid/attempt fields and their cross-state checks; never edit applied V1 or repair history.
-- Request reverse order_id, deadline=min(close,locked-time+10m), snapshot and quota-state consistency
-- require T2 assertions as documented; cross-table CHECK is intentionally not fabricated.
