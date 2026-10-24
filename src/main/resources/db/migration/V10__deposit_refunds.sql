ALTER TABLE deposit_payment ADD COLUMN refund_status text NOT NULL DEFAULT 'NONE'
    CHECK (refund_status IN ('NONE', 'PENDING', 'DONE', 'FORFEITED'));
ALTER TABLE deposit_event ADD COLUMN from_refund_status text
    CHECK (from_refund_status IN ('NONE', 'PENDING', 'DONE', 'FORFEITED'));
ALTER TABLE deposit_event ADD COLUMN to_refund_status text
    CHECK (to_refund_status IN ('NONE', 'PENDING', 'DONE', 'FORFEITED'));
ALTER TABLE deposit_event ADD COLUMN confirmed_by_user_id uuid REFERENCES app_user(id);

-- Preserve the cancellation policy shown at creation; legacy bookings use the shop policy.
ALTER TABLE appointment ADD COLUMN cancel_hours_snapshot integer CHECK (cancel_hours_snapshot >= 0);
