-- Dev fixture context is local to this Flyway transaction.
SELECT set_config('app.barbershop_id', '10000000-0000-0000-0000-000000000001', true);
-- Loaded exclusively through application-dev.yaml. Synthetic public demo data only.
INSERT INTO barbershop (id, slug, name, logo_url, primary_color, secondary_color)
VALUES ('10000000-0000-0000-0000-000000000001', 'barberia-piloto', 'Barbería Piloto',
        NULL, '#1F2937', '#F59E0B')
ON CONFLICT DO NOTHING;

INSERT INTO barber (id, barbershop_id, name, bio) VALUES
('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 'Andrés', 'Cortes clásicos'),
('20000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001', 'Camilo', 'Degradados'),
('20000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000001', 'Daniel', 'Corte y barba')
ON CONFLICT DO NOTHING;

INSERT INTO service (id, barbershop_id, name, duration_minutes, price_cop) VALUES
('30000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 'Corte', 30, 25000),
('30000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001', 'Corte + barba', 60, 40000),
('30000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000001', 'Degradado', 45, 30000),
('30000000-0000-0000-0000-000000000004', '10000000-0000-0000-0000-000000000001', 'Barba', 20, 18000)
ON CONFLICT DO NOTHING;

INSERT INTO barber_service (id, barbershop_id, barber_id, service_id)
SELECT gen_random_uuid(), b.barbershop_id, b.id, s.id
FROM barber b JOIN service s ON s.barbershop_id = b.barbershop_id
WHERE b.id IN ('20000000-0000-0000-0000-000000000001',
               '20000000-0000-0000-0000-000000000002',
               '20000000-0000-0000-0000-000000000003')
  AND s.id IN ('30000000-0000-0000-0000-000000000001',
               '30000000-0000-0000-0000-000000000002',
               '30000000-0000-0000-0000-000000000003',
               '30000000-0000-0000-0000-000000000004')
ON CONFLICT DO NOTHING;

-- 09:00–18:00 in the shop's local timezone, Monday to Saturday (ISO 1–6).
INSERT INTO working_hours (id, barbershop_id, barber_id, day_of_week, start_time, end_time)
SELECT gen_random_uuid(), b.barbershop_id, b.id, d, TIME '09:00', TIME '18:00'
FROM barber b CROSS JOIN generate_series(1, 6) AS d
WHERE b.id IN ('20000000-0000-0000-0000-000000000001',
               '20000000-0000-0000-0000-000000000002',
               '20000000-0000-0000-0000-000000000003')
ON CONFLICT DO NOTHING;
