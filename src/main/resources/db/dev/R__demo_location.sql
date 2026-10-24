SELECT set_config('app.barbershop_id', '10000000-0000-0000-0000-000000000001', true);
-- Synthetic address for the demo; replace before publishing the pilot shop.
UPDATE barbershop SET address = 'Calle de ejemplo 123', city = 'Bogotá'
WHERE id = '10000000-0000-0000-0000-000000000001';
