-- UTC instants; local weekly schedules use ISO weekdays (Monday=1, Sunday=7).
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE barbershop (
    id uuid PRIMARY KEY,
    slug varchar(100) NOT NULL UNIQUE,
    name varchar(150) NOT NULL,
    logo_url varchar(2048),
    primary_color varchar(7) NOT NULL,
    secondary_color varchar(7) NOT NULL,
    timezone varchar(64) NOT NULL DEFAULT 'America/Bogota',
    deposit_percent integer NOT NULL DEFAULT 30,
    cancel_hours integer NOT NULL DEFAULT 24,
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_barbershop_slug CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT ck_barbershop_colors CHECK (primary_color ~ '^#[0-9A-Fa-f]{6}$' AND secondary_color ~ '^#[0-9A-Fa-f]{6}$'),
    CONSTRAINT ck_barbershop_deposit CHECK (deposit_percent BETWEEN 0 AND 100),
    CONSTRAINT ck_barbershop_cancel CHECK (cancel_hours >= 0)
);

CREATE TABLE barber (
    id uuid PRIMARY KEY,
    barbershop_id uuid NOT NULL,
    name varchar(150) NOT NULL,
    bio text,
    photo_url varchar(2048),
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_barber_barbershop FOREIGN KEY (barbershop_id) REFERENCES barbershop(id),
    CONSTRAINT uq_barber_tenant_id UNIQUE (barbershop_id, id)
);

CREATE INDEX idx_barber_barbershop ON barber(barbershop_id);

CREATE TABLE service (
    id uuid PRIMARY KEY,
    barbershop_id uuid NOT NULL,
    name varchar(150) NOT NULL,
    description text,
    duration_minutes integer NOT NULL,
    price_cop bigint NOT NULL,
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_service_barbershop FOREIGN KEY (barbershop_id) REFERENCES barbershop(id),
    CONSTRAINT uq_service_tenant_id UNIQUE (barbershop_id, id),
    CONSTRAINT ck_service_duration CHECK (duration_minutes > 0),
    CONSTRAINT ck_service_price CHECK (price_cop >= 0)
);

CREATE INDEX idx_service_barbershop ON service(barbershop_id);

CREATE TABLE barber_service (
    id uuid PRIMARY KEY,
    barbershop_id uuid NOT NULL,
    barber_id uuid NOT NULL,
    service_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_barber_service_barbershop FOREIGN KEY (barbershop_id) REFERENCES barbershop(id),
    CONSTRAINT fk_barber_service_barber FOREIGN KEY (barbershop_id, barber_id) REFERENCES barber(barbershop_id, id),
    CONSTRAINT fk_barber_service_service FOREIGN KEY (barbershop_id, service_id) REFERENCES service(barbershop_id, id),
    CONSTRAINT uq_barber_service UNIQUE (barbershop_id, barber_id, service_id)
);

CREATE INDEX idx_barber_service_barbershop ON barber_service(barbershop_id);

CREATE TABLE working_hours (
    id uuid PRIMARY KEY,
    barbershop_id uuid NOT NULL,
    barber_id uuid NOT NULL,
    day_of_week integer NOT NULL,
    start_time time NOT NULL,
    end_time time NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_working_hours_barbershop FOREIGN KEY (barbershop_id) REFERENCES barbershop(id),
    CONSTRAINT fk_working_hours_barber FOREIGN KEY (barbershop_id, barber_id) REFERENCES barber(barbershop_id, id),
    CONSTRAINT ck_working_hours_day CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT ck_working_hours_interval CHECK (end_time > start_time),
    CONSTRAINT uq_working_hours UNIQUE (barbershop_id, barber_id, day_of_week, start_time)
);

CREATE INDEX idx_working_hours_barbershop ON working_hours(barbershop_id);

CREATE TABLE time_off (
    id uuid PRIMARY KEY,
    barbershop_id uuid NOT NULL,
    barber_id uuid NOT NULL,
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    reason varchar(255),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_time_off_barbershop FOREIGN KEY (barbershop_id) REFERENCES barbershop(id),
    CONSTRAINT fk_time_off_barber FOREIGN KEY (barbershop_id, barber_id) REFERENCES barber(barbershop_id, id),
    CONSTRAINT ck_time_off_interval CHECK (ends_at > starts_at)
);

CREATE INDEX idx_time_off_barbershop ON time_off(barbershop_id);

CREATE TABLE customer (
    id uuid PRIMARY KEY,
    barbershop_id uuid NOT NULL,
    phone varchar(16) NOT NULL,
    name varchar(150) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_customer_barbershop FOREIGN KEY (barbershop_id) REFERENCES barbershop(id),
    CONSTRAINT uq_customer_tenant_id UNIQUE (barbershop_id, id),
    CONSTRAINT ck_customer_phone CHECK (phone ~ '^\+[1-9][0-9]{1,14}$'),
    CONSTRAINT uq_customer_phone UNIQUE (barbershop_id, phone)
);

CREATE INDEX idx_customer_barbershop ON customer(barbershop_id);

CREATE TABLE appointment (
    id uuid PRIMARY KEY,
    barbershop_id uuid NOT NULL,
    barber_id uuid NOT NULL,
    service_id uuid NOT NULL,
    customer_id uuid NOT NULL,
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    status varchar(24) NOT NULL,
    price_cop bigint NOT NULL,
    deposit_cop bigint NOT NULL,
    payment_reference varchar(32) NOT NULL UNIQUE,
    hold_expires_at timestamptz,
    source varchar(16) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_appointment_barbershop FOREIGN KEY (barbershop_id) REFERENCES barbershop(id),
    CONSTRAINT fk_appointment_barber FOREIGN KEY (barbershop_id, barber_id) REFERENCES barber(barbershop_id, id),
    CONSTRAINT fk_appointment_service FOREIGN KEY (barbershop_id, service_id) REFERENCES service(barbershop_id, id),
    CONSTRAINT fk_appointment_customer FOREIGN KEY (barbershop_id, customer_id) REFERENCES customer(barbershop_id, id),
    CONSTRAINT ck_appointment_interval CHECK (ends_at > starts_at),
    CONSTRAINT ck_appointment_status CHECK (status IN ('PENDING_PAYMENT', 'CONFIRMED', 'COMPLETED', 'NO_SHOW', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_appointment_source CHECK (source IN ('APP', 'MANUAL')),
    CONSTRAINT ck_appointment_amounts CHECK (price_cop >= 0 AND deposit_cop BETWEEN 0 AND price_cop),
    CONSTRAINT ck_appointment_version CHECK (version >= 0),
    CONSTRAINT ck_appointment_reference CHECK (length(trim(payment_reference)) > 0),
    CONSTRAINT ex_appointment_barber_overlap EXCLUDE USING gist (barber_id WITH =, tstzrange(starts_at, ends_at, '[)') WITH &&) WHERE (status IN ('PENDING_PAYMENT', 'CONFIRMED'))
);

CREATE INDEX idx_appointment_barbershop ON appointment(barbershop_id);

-- Keep audit timestamps correct even for direct SQL updates.
CREATE FUNCTION set_updated_at() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.created_at := OLD.created_at;
    NEW.updated_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE FUNCTION prevent_tenant_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.barbershop_id IS DISTINCT FROM OLD.barbershop_id THEN
        RAISE EXCEPTION 'barbershop_id is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_barbershop_updated_at BEFORE UPDATE ON barbershop
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_barber_updated_at BEFORE UPDATE ON barber
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_barber_tenant BEFORE UPDATE ON barber
    FOR EACH ROW EXECUTE FUNCTION prevent_tenant_change();
CREATE TRIGGER trg_service_updated_at BEFORE UPDATE ON service
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_service_tenant BEFORE UPDATE ON service
    FOR EACH ROW EXECUTE FUNCTION prevent_tenant_change();
CREATE TRIGGER trg_barber_service_updated_at BEFORE UPDATE ON barber_service
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_barber_service_tenant BEFORE UPDATE ON barber_service
    FOR EACH ROW EXECUTE FUNCTION prevent_tenant_change();
CREATE TRIGGER trg_working_hours_updated_at BEFORE UPDATE ON working_hours
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_working_hours_tenant BEFORE UPDATE ON working_hours
    FOR EACH ROW EXECUTE FUNCTION prevent_tenant_change();
CREATE TRIGGER trg_time_off_updated_at BEFORE UPDATE ON time_off
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_time_off_tenant BEFORE UPDATE ON time_off
    FOR EACH ROW EXECUTE FUNCTION prevent_tenant_change();
CREATE TRIGGER trg_customer_updated_at BEFORE UPDATE ON customer
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_customer_tenant BEFORE UPDATE ON customer
    FOR EACH ROW EXECUTE FUNCTION prevent_tenant_change();
CREATE TRIGGER trg_appointment_updated_at BEFORE UPDATE ON appointment
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_appointment_tenant BEFORE UPDATE ON appointment
    FOR EACH ROW EXECUTE FUNCTION prevent_tenant_change();
