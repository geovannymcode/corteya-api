-- Test-only legacy schema before phase 3B: proves V5 also protects tables beyond
-- the current business table list. This resource is never in production locations.
CREATE TABLE legacy_tenant_note (
    id uuid PRIMARY KEY,
    barbershop_id uuid NOT NULL REFERENCES barbershop(id),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
