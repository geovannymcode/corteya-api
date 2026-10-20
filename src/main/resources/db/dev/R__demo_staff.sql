-- Dev fixture context is local to this Flyway transaction.
SELECT set_config('app.barbershop_id', '10000000-0000-0000-0000-000000000001', true);
-- Synthetic phone numbers: use the dev log sender, never send production messages to these.
INSERT INTO app_user (id, phone, name) VALUES
('50000000-0000-0000-0000-000000000001', '+573000000001', 'Propietario de prueba'),
('50000000-0000-0000-0000-000000000002', '+573000000002', 'Barbero de prueba')
ON CONFLICT DO NOTHING;

INSERT INTO staff_membership (id, user_id, barbershop_id, role, barber_id) VALUES
('60000000-0000-0000-0000-000000000001', '50000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 'OWNER', NULL),
('60000000-0000-0000-0000-000000000002', '50000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001', 'BARBER', '20000000-0000-0000-0000-000000000001')
ON CONFLICT DO NOTHING;
