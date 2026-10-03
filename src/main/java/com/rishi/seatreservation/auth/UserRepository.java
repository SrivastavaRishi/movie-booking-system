package com.rishi.seatreservation.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class UserRepository {

    /** A stored user: just what login needs. */
    public static class UserRow {
        public final UUID id;
        public final String email;
        public final String passwordHash;
        public final String role;

        UserRow(UUID id, String email, String passwordHash, String role) {
            this.id = id;
            this.email = email;
            this.passwordHash = passwordHash;
            this.role = role;
        }
    }

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts a USER. Returns false if the email is already registered (no error, no race). */
    public boolean insertUser(UUID id, String email, String passwordHash) {
        int rows = jdbc.update(
                "INSERT INTO users (id, email, password_hash, role) VALUES (?, ?, ?, 'USER') "
                        + "ON CONFLICT (email) DO NOTHING",
                id, email, passwordHash);
        return rows == 1;
    }

    public UserRow findByEmail(String email) {
        List<UserRow> rows = jdbc.query(
                "SELECT id, email, password_hash, role FROM users WHERE email = ?",
                (rs, i) -> new UserRow(rs.getObject("id", UUID.class), rs.getString("email"),
                        rs.getString("password_hash"), rs.getString("role")),
                email);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
