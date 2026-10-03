-- Single venue, single admin (see ASSUMPTIONS.md).
-- Login: admin@seatbooking.local / Admin@12345 (bcrypt hash below, cost 10).
INSERT INTO users (user_id, password_hash, role)
VALUES ('admin@seatbooking.local', '$2a$10$S8Q.pyExdympnPIVOQtz9uJ.f4VJDfFt3gJXTWLO9q04yJtbNiuPS', 'ADMIN')
ON CONFLICT (user_id) DO NOTHING;
