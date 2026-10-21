-- Global identity directory, deliberately outside RLS. Resolves verified identities
-- across shops (including inactive ones) without querying tenant data without context.
CREATE TABLE customer_identity (
    id uuid PRIMARY KEY,
    barbershop_id uuid NOT NULL REFERENCES barbershop(id),
    user_id uuid REFERENCES app_user(id),
    phone text CHECK (phone IS NULL OR phone ~ '^\+[1-9][0-9]{1,14}$'),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_customer_identity_customer FOREIGN KEY (barbershop_id, id) REFERENCES customer(barbershop_id, id) ON DELETE CASCADE
);
CREATE INDEX idx_customer_identity_barbershop ON customer_identity(barbershop_id);
CREATE INDEX idx_customer_identity_user ON customer_identity(user_id) WHERE user_id IS NOT NULL;
CREATE INDEX idx_customer_identity_phone ON customer_identity(phone) WHERE phone IS NOT NULL;
CREATE TRIGGER trg_customer_identity_updated_at BEFORE UPDATE ON customer_identity
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_customer_identity_tenant BEFORE UPDATE ON customer_identity
    FOR EACH ROW EXECUTE FUNCTION prevent_tenant_change();

INSERT INTO customer_identity(id, barbershop_id, user_id, phone)
SELECT id, barbershop_id, user_id, phone FROM customer WHERE phone IS NOT NULL;

-- Invoker privileges only. No SECURITY DEFINER, bypass role or system mode.
CREATE FUNCTION sync_customer_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.phone IS NULL THEN
        DELETE FROM customer_identity WHERE id = NEW.id;
    ELSE
        INSERT INTO customer_identity(id, barbershop_id, user_id, phone)
        VALUES (NEW.id, NEW.barbershop_id, NEW.user_id, NEW.phone)
        ON CONFLICT (id) DO UPDATE SET user_id = EXCLUDED.user_id, phone = EXCLUDED.phone,
            updated_at = CURRENT_TIMESTAMP;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_customer_identity AFTER INSERT OR UPDATE OF phone, user_id ON customer
    FOR EACH ROW EXECUTE FUNCTION sync_customer_identity();

-- NULLIF also handles the empty custom GUC left after a transaction on a pooled
-- connection. Missing OR empty context must yield zero rows, never an invalid UUID.
DO $$
DECLARE tenant_table text;
BEGIN
    FOR tenant_table IN
        SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')
          AND EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.oid
                      AND a.attname = 'barbershop_id' AND NOT a.attisdropped)
          AND c.relname NOT IN ('staff_membership', 'customer_identity')
    LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', tenant_table);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', tenant_table);
        EXECUTE format('CREATE POLICY tenant_isolation ON public.%I
            USING (barbershop_id = NULLIF(current_setting(''app.barbershop_id'', true), '''')::uuid)
            WITH CHECK (barbershop_id = NULLIF(current_setting(''app.barbershop_id'', true), '''')::uuid)', tenant_table);
    END LOOP;
END;
$$;

ALTER TABLE barbershop ENABLE ROW LEVEL SECURITY;
ALTER TABLE barbershop FORCE ROW LEVEL SECURITY;
-- Public discovery sees active shops; a trusted tenant context can also access its
-- own inactive shop for account erasure/history and future reactivation.
CREATE POLICY shop_read ON barbershop FOR SELECT
    USING (active OR id = NULLIF(current_setting('app.barbershop_id', true), '')::uuid);
CREATE POLICY shop_insert ON barbershop FOR INSERT
    WITH CHECK (id = NULLIF(current_setting('app.barbershop_id', true), '')::uuid);
CREATE POLICY shop_update ON barbershop FOR UPDATE
    USING (id = NULLIF(current_setting('app.barbershop_id', true), '')::uuid)
    WITH CHECK (id = NULLIF(current_setting('app.barbershop_id', true), '')::uuid);
CREATE POLICY shop_delete ON barbershop FOR DELETE
    USING (id = NULLIF(current_setting('app.barbershop_id', true), '')::uuid);

-- app_user, otp_challenge, refresh_token, staff_membership and customer_identity
-- remain global identity tables. All their access is scoped by verified identity.
GRANT USAGE ON SCHEMA public TO corteya_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO corteya_app;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO corteya_app;
ALTER DEFAULT PRIVILEGES FOR ROLE corteya_owner IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO corteya_app;
ALTER DEFAULT PRIVILEGES FOR ROLE corteya_owner IN SCHEMA public GRANT USAGE ON SEQUENCES TO corteya_app;
REVOKE INSERT, UPDATE, DELETE ON flyway_schema_history FROM corteya_app;
