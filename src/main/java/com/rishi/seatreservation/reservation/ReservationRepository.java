package com.rishi.seatreservation.reservation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * All reservation SQL. Every concurrency decision is made here, by the database, in a single
 * statement: a unique constraint, a conditional upsert, or a row lock. See CONCURRENCY.md.
 */
@Repository
public class ReservationRepository {

    public static class ReservationRow {
        public final UUID id;
        public final UUID showId;
        public final UUID userId;
        public final String requestHash;
        public final List<String> seats;
        public final long amountPaise;
        public final String status;
        public final Instant createdAt;
        public final Instant cancelledAt;

        ReservationRow(UUID id, UUID showId, UUID userId, String requestHash, List<String> seats,
                       long amountPaise, String status, Instant createdAt, Instant cancelledAt) {
            this.id = id;
            this.showId = showId;
            this.userId = userId;
            this.requestHash = requestHash;
            this.seats = seats;
            this.amountPaise = amountPaise;
            this.status = status;
            this.createdAt = createdAt;
            this.cancelledAt = cancelledAt;
        }

        public boolean isConfirmed() {
            return "confirmed".equals(status);
        }
    }

    public static class SeatState {
        public final String label;
        public final String state;

        SeatState(String label, String state) {
            this.label = label;
            this.state = state;
        }
    }

    private static final String COLUMNS =
            "id, show_id, user_id, request_hash, seats, amount_paise, status, created_at, cancelled_at";

    private static final RowMapper<ReservationRow> MAPPER = (rs, i) -> new ReservationRow(
            rs.getObject("id", UUID.class),
            rs.getObject("show_id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getString("request_hash"),
            toList(rs.getArray("seats")),
            rs.getLong("amount_paise"),
            rs.getString("status"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("cancelled_at")));

    private final JdbcTemplate jdbc;

    public ReservationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Step 2 (idempotency). Inserts the reservation unless this user already used this key.
     * If another transaction is inserting the same key right now, this statement waits for it to
     * commit and then sees the conflict -> exactly once. Returns the new row, or null on conflict.
     */
    public ReservationRow insertIfKeyUnused(UUID id, UUID showId, UUID userId, String idempotencyKey,
                                            String requestHash, List<String> seats, long amountPaise) {
        List<ReservationRow> rows = jdbc.query(
                "INSERT INTO reservations (id, show_id, user_id, idempotency_key, request_hash, seats, "
                        + "amount_paise, status) VALUES (?, ?, ?, ?, ?, ?::text[], ?, 'confirmed') "
                        + "ON CONFLICT (user_id, idempotency_key) DO NOTHING "
                        + "RETURNING " + COLUMNS,
                MAPPER, id, showId, userId, idempotencyKey, requestHash, textArray(seats), amountPaise);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public ReservationRow findByUserAndKey(UUID userId, String idempotencyKey) {
        List<ReservationRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM reservations WHERE user_id = ? AND idempotency_key = ?",
                MAPPER, userId, idempotencyKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Step 3 (per-user limit). Adds n to the user's seat count for the show only if the result stays
     * within the limit. The row lock makes one user's parallel requests take turns, and each re-checks
     * the limit. Returns the new count, or null if the limit would be exceeded.
     */
    public Integer addToQuota(UUID showId, UUID userId, int n, int limit) {
        List<Integer> rows = jdbc.query(
                "INSERT INTO user_quota (show_id, user_id, seats_held) VALUES (?, ?, ?) "
                        + "ON CONFLICT (show_id, user_id) DO UPDATE "
                        + "SET seats_held = user_quota.seats_held + EXCLUDED.seats_held "
                        + "WHERE user_quota.seats_held + EXCLUDED.seats_held <= ? "
                        + "RETURNING seats_held",
                (rs, i) -> rs.getInt(1), showId, userId, n, limit);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void subtractFromQuota(UUID showId, UUID userId, int n) {
        jdbc.update("UPDATE user_quota SET seats_held = seats_held - ? WHERE show_id = ? AND user_id = ?",
                n, showId, userId);
    }

    /**
     * Step 4a. Locks the requested seats in label order (sorted locking = no deadlocks between
     * multi-seat requests) and returns their current state. Under FOR UPDATE, the state returned is the
     * latest committed one, so it is safe to decide on.
     */
    public List<SeatState> lockSeats(UUID showId, List<String> sortedLabels) {
        return jdbc.query(
                "SELECT label, state FROM seats WHERE show_id = ? AND label = ANY(?::text[]) "
                        + "ORDER BY label FOR UPDATE",
                (rs, i) -> new SeatState(rs.getString("label"), rs.getString("state")),
                showId, textArray(sortedLabels));
    }

    /** Step 4b. Conditional update: only seats still available are confirmed. Returns rows changed. */
    public int confirmSeats(UUID showId, List<String> labels, UUID reservationId) {
        return jdbc.update(
                "UPDATE seats SET state = 'confirmed', reservation_id = ?, updated_at = now() "
                        + "WHERE show_id = ? AND label = ANY(?::text[]) AND state = 'available'",
                reservationId, showId, textArray(labels));
    }

    /** Ownership is part of the WHERE clause: another user's reservation is simply "not found". */
    public ReservationRow findOwned(UUID id, UUID userId) {
        List<ReservationRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM reservations WHERE id = ? AND user_id = ?", MAPPER, id, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public ReservationRow lockForUpdate(UUID id) {
        List<ReservationRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM reservations WHERE id = ? FOR UPDATE", MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Releases only the seats that still point at this reservation, so a cancel can never free a seat
     * now confirmed to someone else. Seats are locked in label order first, like reserve does.
     */
    public int releaseSeats(UUID reservationId) {
        jdbc.query("SELECT label FROM seats WHERE reservation_id = ? ORDER BY label FOR UPDATE",
                (rs, i) -> rs.getString(1), reservationId);
        return jdbc.update("UPDATE seats SET state = 'available', reservation_id = NULL, updated_at = now() "
                + "WHERE reservation_id = ?", reservationId);
    }

    public ReservationRow markCancelled(UUID id) {
        List<ReservationRow> rows = jdbc.query(
                "UPDATE reservations SET status = 'cancelled', cancelled_at = now() WHERE id = ? "
                        + "RETURNING " + COLUMNS, MAPPER, id);
        return rows.get(0);
    }

    private static SqlArrayValue textArray(List<String> values) {
        return new SqlArrayValue("text", values.toArray());
    }

    private static List<String> toList(Array array) throws SQLException {
        if (array == null) {
            return Collections.emptyList();
        }
        return Arrays.asList((String[]) array.getArray());
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
