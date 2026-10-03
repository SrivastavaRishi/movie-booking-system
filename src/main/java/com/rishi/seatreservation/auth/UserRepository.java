package com.rishi.seatreservation.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class UserRepository {

    /** A stored user: just what login needs. */
    public static class UserRow {
        public final String userId;
        public final String passwordHash;
        public final String role;

        UserRow(String userId, String passwordHash, String role) {
            this.userId = userId;
            this.passwordHash = passwordHash;
            this.role = role;
        }
    }

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts a USER. Returns false if the user id is already taken (no error, no race). */
    public boolean insertUser(String userId, String passwordHash) {
        int rows = jdbc.update(
                "INSERT INTO users (user_id, password_hash, role) VALUES (?, ?, 'USER') "
                        + "ON CONFLICT (user_id) DO NOTHING",
                userId, passwordHash);
        return rows == 1;
    }

    public UserRow find(String userId) {
        List<UserRow> rows = jdbc.query(
                "SELECT user_id, password_hash, role FROM users WHERE user_id = ?",
                (rs, i) -> new UserRow(rs.getString("user_id"), rs.getString("password_hash"), rs.getString("role")),
                userId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
