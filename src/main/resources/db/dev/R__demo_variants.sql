-- Synthetic fixtures, dev only. Never send messages to these invented numbers.
SELECT set_config('app.barbershop_id', '10000000-0000-0000-0000-000000000002', true);
INSERT INTO barbershop (id, slug, name, primary_color, secondary_color, address, city)
VALUES ('10000000-0000-0000-0000-000000000002', 'barberia-breb', 'Barbería Demo Bre-B', '#112233', '#445566', 'Calle ficticia 2', 'Bogotá') ON CONFLICT DO NOTHING;
INSERT INTO barber (id, barbershop_id, name) VALUES ('20000000-0000-0000-0000-000000000020', '10000000-0000-0000-0000-000000000002', 'Barbero Demo Bre-B') ON CONFLICT DO NOTHING;
INSERT INTO service (id, barbershop_id, name, duration_minutes, price_cop)
VALUES ('30000000-0000-0000-0000-000000000020', '10000000-0000-0000-0000-000000000002', 'Corte Demo', 30, 30000) ON CONFLICT DO NOTHING;
INSERT INTO barber_service (id, barbershop_id, barber_id, service_id)
VALUES (gen_random_uuid(), '10000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000020', '30000000-0000-0000-0000-000000000020') ON CONFLICT DO NOTHING;
INSERT INTO working_hours (id, barbershop_id, barber_id, day_of_week, start_time, end_time)
SELECT gen_random_uuid(), '10000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000020', d, TIME '09:00', TIME '18:00' FROM generate_series(1,6) d ON CONFLICT DO NOTHING;
INSERT INTO app_user (id, phone, name) VALUES
('50000000-0000-0000-0000-000000000003', '+573000000003', 'Owner Demo Bre-B'), ('50000000-0000-0000-0000-000000000004', '+573000000004', 'Staff Demo Bre-B') ON CONFLICT DO NOTHING;
INSERT INTO staff_membership (id, user_id, barbershop_id, role, barber_id) VALUES
(gen_random_uuid(), '50000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000002', 'OWNER', NULL), (gen_random_uuid(), '50000000-0000-0000-0000-000000000004', '10000000-0000-0000-0000-000000000002', 'BARBER', '20000000-0000-0000-0000-000000000020') ON CONFLICT DO NOTHING;

SELECT set_config('app.barbershop_id', '10000000-0000-0000-0000-000000000003', true);
INSERT INTO barbershop (id, slug, name, primary_color, secondary_color, address, city)
VALUES ('10000000-0000-0000-0000-000000000003', 'barberia-libre', 'Barbería Demo Libre', '#112233', '#445566', 'Calle ficticia 3', 'Bogotá') ON CONFLICT DO NOTHING;
INSERT INTO barber (id, barbershop_id, name) VALUES ('20000000-0000-0000-0000-000000000030', '10000000-0000-0000-0000-000000000003', 'Barbero Demo Libre') ON CONFLICT DO NOTHING;
INSERT INTO service (id, barbershop_id, name, duration_minutes, price_cop)
VALUES ('30000000-0000-0000-0000-000000000030', '10000000-0000-0000-0000-000000000003', 'Corte Demo', 30, 30000) ON CONFLICT DO NOTHING;
INSERT INTO barber_service (id, barbershop_id, barber_id, service_id)
VALUES (gen_random_uuid(), '10000000-0000-0000-0000-000000000003', '20000000-0000-0000-0000-000000000030', '30000000-0000-0000-0000-000000000030') ON CONFLICT DO NOTHING;
INSERT INTO working_hours (id, barbershop_id, barber_id, day_of_week, start_time, end_time)
SELECT gen_random_uuid(), '10000000-0000-0000-0000-000000000003', '20000000-0000-0000-0000-000000000030', d, TIME '09:00', TIME '18:00' FROM generate_series(1,6) d ON CONFLICT DO NOTHING;
INSERT INTO app_user (id, phone, name) VALUES
('50000000-0000-0000-0000-000000000005', '+573000000005', 'Owner Demo Libre'), ('50000000-0000-0000-0000-000000000006', '+573000000006', 'Staff Demo Libre') ON CONFLICT DO NOTHING;
INSERT INTO staff_membership (id, user_id, barbershop_id, role, barber_id) VALUES
(gen_random_uuid(), '50000000-0000-0000-0000-000000000005', '10000000-0000-0000-0000-000000000003', 'OWNER', NULL), (gen_random_uuid(), '50000000-0000-0000-0000-000000000006', '10000000-0000-0000-0000-000000000003', 'BARBER', '20000000-0000-0000-0000-000000000030') ON CONFLICT DO NOTHING;
