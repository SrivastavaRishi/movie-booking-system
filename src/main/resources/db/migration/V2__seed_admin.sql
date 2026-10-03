-- Single venue, single admin (see ASSUMPTIONS.md).
-- Login: admin@seatbooking.local / Admin@12345 (bcrypt hash below, cost 10).
INSERT INTO users (id, email, password_hash, role)
VALUES (gen_random_uuid(), 'admin@seatbooking.local', '$2a$10$S8Q.pyExdympnPIVOQtz9uJ.f4VJDfFt3gJXTWLO9q04yJtbNiuPS', 'ADMIN')
ON CONFLICT (email) DO NOTHING;
