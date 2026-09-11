-- BIZ-02: forward-only close lifecycle. MySQL DDL commits per statement, not per script.
-- Preserve V1 and all existing HOLD rows. No payment or Outbox source changes.
ALTER TABLE booking_order
  DROP CHECK ck_order_hold_only,
  ADD COLUMN closed_at DATETIME(3) NULL,
  ADD COLUMN close_reason VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD CONSTRAINT ck_order_close_state CHECK (
    (status = 'HOLD' AND closed_at IS NULL AND close_reason IS NULL) OR
    (status = 'CANCELLED' AND closed_at IS NOT NULL AND close_reason IS NOT NULL
      AND close_reason = 'USER_CANCELLED' AND closed_at >= hold_created_at AND closed_at < pay_expires_at) OR
    (status = 'EXPIRED' AND closed_at IS NOT NULL AND close_reason IS NOT NULL
      AND close_reason = 'PAYMENT_DEADLINE_EXCEEDED' AND closed_at >= pay_expires_at)
  );

ALTER TABLE inventory_ledger
  DROP CHECK ck_ledger_reserve,
  ADD CONSTRAINT ck_ledger_action CHECK (
    available_after >= 0 AND ((action = 'RESERVE' AND delta = -1) OR (action = 'RELEASE' AND delta = 1))
  );
