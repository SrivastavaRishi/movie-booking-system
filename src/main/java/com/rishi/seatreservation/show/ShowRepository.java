package com.rishi.seatreservation.show;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class ShowRepository {

    public static class ShowRow {
        public final UUID id;
        public final String name;
        public final String status;
        public final long pricePaise;
        public final int perUserLimit;
        public final int totalSeats;
        public final Instant createdAt;
        public final Instant cancelledAt;

        ShowRow(UUID id, String name, String status, long pricePaise, int perUserLimit, int totalSeats,
                Instant createdAt, Instant cancelledAt) {
            this.id = id;
            this.name = name;
            this.status = status;
            this.pricePaise = pricePaise;
            this.perUserLimit = perUserLimit;
            this.totalSeats = totalSeats;
            this.createdAt = createdAt;
            this.cancelledAt = cancelledAt;
        }

        public boolean isCancelled() {
            return "cancelled".equals(status);
        }
    }

    private static final String SHOW_COLUMNS =
            "id, name, status, price_paise, per_user_limit, total_seats, created_at, cancelled_at";

    private static final RowMapper<ShowRow> SHOW_MAPPER = (rs, i) -> new ShowRow(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getString("status"),
            rs.getLong("price_paise"),
            rs.getInt("per_user_limit"),
            rs.getInt("total_seats"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("cancelled_at")));

    private final JdbcTemplate jdbc;

    public ShowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertShow(UUID id, String name, long pricePaise, int perUserLimit, int totalSeats) {
        jdbc.update("INSERT INTO shows (id, name, price_paise, per_user_limit, total_seats, status) "
                + "VALUES (?, ?, ?, ?, ?, 'active')", id, name, pricePaise, perUserLimit, totalSeats);
    }

    /** All seats in one statement; position keeps the order the admin sent them in. */
    public void insertSeats(UUID showId, String[] labels) {
        jdbc.update("INSERT INTO seats (show_id, label, position, state) "
                        + "SELECT ?, s.label, s.pos::int, 'available' "
                        + "FROM unnest(?::text[]) WITH ORDINALITY AS s(label, pos)",
                showId, new SqlArrayValue("text", (Object[]) labels));
    }

    public ShowRow find(UUID id) {
        List<ShowRow> rows = jdbc.query("SELECT " + SHOW_COLUMNS + " FROM shows WHERE id = ?", SHOW_MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Shared lock on the show row, taken first by every reserve / cancel-reservation.
     * Many can hold it at once; cancel-show's exclusive lock waits for all of them.
     */
    public ShowRow lockForShare(UUID id) {
        List<ShowRow> rows = jdbc.query(
                "SELECT " + SHOW_COLUMNS + " FROM shows WHERE id = ? FOR SHARE", SHOW_MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Exclusive lock on the show row (cancel-show): waits for in-flight reserves, blocks new ones. */
    public ShowRow lockForUpdate(UUID id) {
        List<ShowRow> rows = jdbc.query(
                "SELECT " + SHOW_COLUMNS + " FROM shows WHERE id = ? FOR UPDATE", SHOW_MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** One query, so the seat list (and the counts derived from it) is a single consistent snapshot. */
    public List<SeatView> findSeats(UUID showId) {
        return jdbc.query("SELECT label, state FROM seats WHERE show_id = ? ORDER BY position",
                (rs, i) -> new SeatView(rs.getString("label"), rs.getString("state")), showId);
    }

    public void markCancelled(UUID id) {
        jdbc.update("UPDATE shows SET status = 'cancelled', cancelled_at = now() WHERE id = ?", id);
    }

    public int cancelReservationsOfShow(UUID showId) {
        return jdbc.update("UPDATE reservations SET status = 'cancelled_by_admin', cancelled_at = now() "
                + "WHERE show_id = ? AND status = 'confirmed'", showId);
    }

    public void resetQuotas(UUID showId) {
        jdbc.update("UPDATE user_quota SET seats_held = 0 WHERE show_id = ?", showId);
    }

    public int releaseAllSeats(UUID showId) {
        return jdbc.update("UPDATE seats SET state = 'available', reservation_id = NULL, updated_at = now() "
                + "WHERE show_id = ? AND state <> 'available'", showId);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
