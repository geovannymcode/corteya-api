ALTER TABLE barbershop ADD COLUMN address text;
ALTER TABLE barbershop ADD COLUMN city text;
-- Preserve receiving instructions when resuming an existing manual deposit.
ALTER TABLE deposit_payment ADD COLUMN breb_settings jsonb;
