CREATE TABLE barbershop_payment_settings (
    id uuid PRIMARY KEY,
    barbershop_id uuid NOT NULL UNIQUE REFERENCES barbershop(id),
    deposit_mode text NOT NULL CHECK (deposit_mode IN ('NONE', 'BREB_MANUAL', 'WOMPI')),
    hold_minutes integer NOT NULL CHECK (hold_minutes BETWEEN 1 AND 1440),
    breb_key_type text CHECK (breb_key_type IN ('CELULAR', 'ALIAS', 'CORREO', 'DOCUMENTO')),
    breb_key text,
    breb_holder_name text,
    wompi_environment text CHECK (wompi_environment IN ('SANDBOX', 'PRODUCTION')),
    wompi_public_key text,
    wompi_private_key_enc text,
    wompi_integrity_secret_enc text,
    wompi_events_secret_enc text,
    wompi_webhook_id text UNIQUE,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (deposit_mode <> 'BREB_MANUAL' OR (breb_key_type IS NOT NULL AND breb_key IS NOT NULL AND breb_holder_name IS NOT NULL)),
    CHECK (deposit_mode <> 'WOMPI' OR (wompi_environment IS NOT NULL AND wompi_public_key IS NOT NULL
        AND wompi_private_key_enc IS NOT NULL AND wompi_integrity_secret_enc IS NOT NULL
        AND wompi_events_secret_enc IS NOT NULL AND wompi_webhook_id IS NOT NULL))
);
CREATE TRIGGER trg_payment_settings_updated_at BEFORE UPDATE ON barbershop_payment_settings
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_payment_settings_tenant BEFORE UPDATE ON barbershop_payment_settings
    FOR EACH ROW EXECUTE FUNCTION prevent_tenant_change();
ALTER TABLE barbershop_payment_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE barbershop_payment_settings FORCE ROW LEVEL SECURITY;
CREATE POLICY payment_settings_tenant ON barbershop_payment_settings
    USING (barbershop_id = NULLIF(current_setting('app.barbershop_id', true), '')::uuid)
    WITH CHECK (barbershop_id = NULLIF(current_setting('app.barbershop_id', true), '')::uuid);
