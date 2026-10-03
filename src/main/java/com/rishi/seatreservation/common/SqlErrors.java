package com.rishi.seatreservation.common;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

/** Helpers to classify database errors by PostgreSQL SQLSTATE. */
public final class SqlErrors {

    public static final String DEADLOCK_DETECTED = "40P01";
    public static final String SERIALIZATION_FAILURE = "40001";
    public static final String LOCK_NOT_AVAILABLE = "55P03"; // lock_timeout
    public static final String QUERY_CANCELED = "57014";     // statement_timeout

    private SqlErrors() {
    }

    public static String sqlState(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof SQLException && ((SQLException) cur).getSQLState() != null) {
                return ((SQLException) cur).getSQLState();
            }
            cur = cur.getCause();
        }
        return null;
    }

    public static boolean isDeadlock(Throwable t) {
        return DEADLOCK_DETECTED.equals(sqlState(t));
    }

    /** Contention or overload: safe for the client to retry, so it maps to 429 instead of 500. */
    public static boolean isRetryable(Throwable t) {
        String state = sqlState(t);
        if (DEADLOCK_DETECTED.equals(state) || SERIALIZATION_FAILURE.equals(state)
                || LOCK_NOT_AVAILABLE.equals(state) || QUERY_CANCELED.equals(state)) {
            return true;
        }
        // HikariCP throws this when no pool connection frees up within connection-timeout.
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof SQLTransientConnectionException) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }
}
