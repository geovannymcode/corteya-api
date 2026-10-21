CREATE TABLE deposit_payment (
    id uuid PRIMARY KEY,
    barbershop_id uuid NOT NULL REFERENCES barbershop(id),
    appointment_id uuid NOT NULL,
    method text NOT NULL CHECK (method IN ('BREB_MANUAL', 'WOMPI')),
    amount_cop bigint NOT NULL CHECK (amount_cop >= 0 AND amount_cop <= 92233720368547758),
    reference text NOT NULL,
    attempt integer NOT NULL CHECK (attempt > 0),
    status text NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'DECLINED', 'VOIDED', 'ERROR', 'EXPIRED', 'LATE_PAYMENT')),
    provider_transaction_id text,
    confirmed_by_user_id uuid REFERENCES app_user(id),
    confirmed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (barbershop_id, id),
    UNIQUE (barbershop_id, reference),
    UNIQUE (barbershop_id, appointment_id, attempt),
    UNIQUE (method, provider_transaction_id),
    FOREIGN KEY (barbershop_id, appointment_id) REFERENCES appointment(barbershop_id, id)
);
CREATE INDEX idx_deposit_payment_appointment ON deposit_payment(barbershop_id, appointment_id);
CREATE TABLE deposit_event (
    id uuid PRIMARY KEY,
    barbershop_id uuid NOT NULL REFERENCES barbershop(id),
    deposit_payment_id uuid NOT NULL,
    from_status text,
    to_status text NOT NULL,
    source text NOT NULL CHECK (source IN ('WEBHOOK', 'STAFF', 'SYSTEM')),
    provider_status text,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (barbershop_id, deposit_payment_id) REFERENCES deposit_payment(barbershop_id, id),
    CHECK (from_status IS NULL OR from_status IN ('PENDING', 'APPROVED', 'DECLINED', 'VOIDED', 'ERROR', 'EXPIRED', 'LATE_PAYMENT')),
    CHECK (to_status IN ('PENDING', 'APPROVED', 'DECLINED', 'VOIDED', 'ERROR', 'EXPIRED', 'LATE_PAYMENT'))
);
CREATE INDEX idx_deposit_event_payment ON deposit_event(barbershop_id, deposit_payment_id, created_at);
DO $$
DECLARE tenant_table text;
BEGIN
    FOREACH tenant_table IN ARRAY ARRAY['deposit_payment', 'deposit_event'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', tenant_table);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', tenant_table);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (barbershop_id = NULLIF(current_setting(''app.barbershop_id'', true), )::uuid) WITH CHECK (barbershop_id = NULLIF(current_setting(app.barbershop_id, true), )::uuid)', tenant_table);
        EXECUTE format('CREATE TRIGGER trg_updated_at BEFORE UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION set_updated_at()', tenant_table);
        EXECUTE format('CREATE TRIGGER trg_tenant BEFORE UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION prevent_tenant_change()', tenant_table);
    END LOOP;
END;
$$;
