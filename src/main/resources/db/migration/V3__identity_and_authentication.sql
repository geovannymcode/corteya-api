-- Global identity and credentials are deliberately not owned by a single barbershop.
CREATE TABLE app_user (
    id uuid PRIMARY KEY,
    phone varchar(16) UNIQUE,
    name varchar(150),
    platform_admin boolean NOT NULL DEFAULT false,
    deleted_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_app_user_phone CHECK (phone ~ '^\+[1-9][0-9]{1,14}$'),
    CONSTRAINT ck_app_user_active_phone CHECK (deleted_at IS NOT NULL OR phone IS NOT NULL)
);

ALTER TABLE customer ALTER COLUMN phone DROP NOT NULL;
ALTER TABLE customer ADD COLUMN user_id uuid REFERENCES app_user(id);
CREATE UNIQUE INDEX uq_customer_user ON customer(barbershop_id, user_id) WHERE user_id IS NOT NULL;
CREATE INDEX idx_customer_user ON customer(user_id) WHERE user_id IS NOT NULL;

CREATE TABLE staff_membership (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES app_user(id),
    barbershop_id uuid NOT NULL REFERENCES barbershop(id),
    role varchar(16) NOT NULL CHECK (role IN ('OWNER', 'BARBER')),
    barber_id uuid,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_staff_membership UNIQUE (user_id, barbershop_id),
    CONSTRAINT ck_staff_membership_barber CHECK (role <> 'BARBER' OR barber_id IS NOT NULL),
    CONSTRAINT fk_staff_membership_barber FOREIGN KEY (barbershop_id, barber_id) REFERENCES barber(barbershop_id, id)
);
CREATE INDEX idx_staff_membership_barbershop ON staff_membership(barbershop_id);

CREATE TABLE otp_challenge (
    id uuid PRIMARY KEY,
    phone varchar(16) NOT NULL CHECK (phone ~ '^\+[1-9][0-9]{1,14}$'),
    code_hash varchar(64) NOT NULL,
    expires_at timestamptz NOT NULL,
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
    consumed_at timestamptz,
    request_ip varchar(45) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_otp_phone_created ON otp_challenge(phone, created_at DESC);
CREATE INDEX idx_otp_ip_created ON otp_challenge(request_ip, created_at DESC);

CREATE TABLE refresh_token (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES app_user(id),
    token_hash varchar(64) NOT NULL UNIQUE,
    family_id uuid NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    replaced_by uuid REFERENCES refresh_token(id),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_refresh_token_user ON refresh_token(user_id);
CREATE INDEX idx_refresh_token_family ON refresh_token(family_id);

CREATE TRIGGER trg_app_user_updated_at BEFORE UPDATE ON app_user
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_staff_membership_updated_at BEFORE UPDATE ON staff_membership
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_staff_membership_tenant BEFORE UPDATE ON staff_membership
    FOR EACH ROW EXECUTE FUNCTION prevent_tenant_change();
CREATE TRIGGER trg_otp_challenge_updated_at BEFORE UPDATE ON otp_challenge
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_refresh_token_updated_at BEFORE UPDATE ON refresh_token
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
