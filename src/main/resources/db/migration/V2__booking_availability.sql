ALTER TABLE barbershop
    ADD COLUMN slot_step_minutes integer NOT NULL DEFAULT 15 CHECK (slot_step_minutes BETWEEN 1 AND 1440),
    ADD COLUMN min_lead_minutes integer NOT NULL DEFAULT 30 CHECK (min_lead_minutes >= 0),
    ADD COLUMN booking_window_days integer NOT NULL DEFAULT 14 CHECK (booking_window_days BETWEEN 1 AND 366),
    ADD COLUMN hold_minutes integer NOT NULL DEFAULT 15 CHECK (hold_minutes > 0);

-- Hash two independently generated UUIDs: 128 output bits without UUID version/variant bits.
-- The default also covers existing rows and legacy SQL inserts, without pgcrypto.
ALTER TABLE appointment ADD COLUMN public_token varchar(32) NOT NULL
    DEFAULT substring(encode(sha256(uuid_send(gen_random_uuid()) || uuid_send(gen_random_uuid())), 'hex') for 32);
ALTER TABLE appointment ADD CONSTRAINT uq_appointment_public_token UNIQUE (public_token);
ALTER TABLE appointment ADD CONSTRAINT ck_appointment_public_token CHECK (public_token ~ '^[0-9a-f]{32}$');

ALTER TABLE appointment DROP CONSTRAINT appointment_payment_reference_key;
ALTER TABLE appointment ADD CONSTRAINT uq_appointment_shop_reference UNIQUE (barbershop_id, payment_reference);

-- Rounding up to COP 1,000 can exceed the price of very cheap services.
ALTER TABLE appointment DROP CONSTRAINT ck_appointment_amounts;
ALTER TABLE appointment ADD CONSTRAINT ck_appointment_amounts CHECK (price_cop >= 0 AND deposit_cop >= 0);

CREATE INDEX idx_appointment_expiring ON appointment(hold_expires_at)
    WHERE status = 'PENDING_PAYMENT';
CREATE INDEX idx_appointment_customer_pending ON appointment(barbershop_id, customer_id)
    WHERE status = 'PENDING_PAYMENT';
CREATE INDEX idx_time_off_barber_interval ON time_off(barbershop_id, barber_id, starts_at, ends_at);
CREATE INDEX idx_working_hours_barber_day ON working_hours(barbershop_id, barber_id, day_of_week);
