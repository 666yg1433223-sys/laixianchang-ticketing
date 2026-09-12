-- Product-only forward migration; the historical isolated IT profile still targets V1-V4.
ALTER TABLE app_user
  ADD COLUMN phone VARCHAR(11) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD COLUMN role VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'USER',
  ADD CONSTRAINT uq_user_phone UNIQUE(phone),
  ADD CONSTRAINT ck_user_role CHECK(role IN ('USER','ADMIN'));
ALTER TABLE event
  ADD COLUMN description TEXT NULL,
  ADD COLUMN venue_text VARCHAR(255) NOT NULL DEFAULT '',
  ADD COLUMN updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3);
ALTER TABLE ticket_tier ADD COLUMN area_label VARCHAR(64) NOT NULL DEFAULT '';
CREATE TABLE catalogue_mutation (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  event_id BIGINT NOT NULL,
  event_session_id BIGINT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_mutation_event FOREIGN KEY(event_id) REFERENCES event(id) ON DELETE RESTRICT,
  CONSTRAINT fk_mutation_session FOREIGN KEY(event_session_id) REFERENCES event_session(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
ALTER TABLE outbox_event
  ADD COLUMN catalogue_mutation_id BIGINT NULL,
  ADD CONSTRAINT fk_outbox_catalogue FOREIGN KEY(catalogue_mutation_id) REFERENCES catalogue_mutation(id) ON DELETE RESTRICT,
  DROP CHECK ck_outbox_typed_source,
  ADD CONSTRAINT ck_outbox_typed_source CHECK(schema_version=1 AND (
    (event_type='BOOKING_REQUESTED' AND booking_request_id IS NOT NULL AND booking_request_id=aggregate_id AND payment_attempt_id IS NULL AND catalogue_mutation_id IS NULL) OR
    (event_type='PAYMENT_RESULT_RECORDED' AND payment_attempt_id IS NOT NULL AND payment_attempt_id=aggregate_id AND booking_request_id IS NULL AND catalogue_mutation_id IS NULL) OR
    (event_type='CATALOG_INVALIDATE' AND catalogue_mutation_id IS NOT NULL AND catalogue_mutation_id=aggregate_id AND booking_request_id IS NULL AND payment_attempt_id IS NULL)
  ));
