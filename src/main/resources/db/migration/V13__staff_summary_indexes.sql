-- Range scans for independent activity, receipts, DONE events and current debt.
CREATE INDEX idx_appointment_shop_starts ON appointment(barbershop_id, starts_at);
CREATE INDEX idx_deposit_payment_shop_confirmed ON deposit_payment(barbershop_id, confirmed_at);
CREATE INDEX idx_deposit_payment_shop_refund ON deposit_payment(barbershop_id, refund_status);
CREATE INDEX idx_deposit_event_shop_refund_created ON deposit_event(barbershop_id, to_refund_status, created_at);
