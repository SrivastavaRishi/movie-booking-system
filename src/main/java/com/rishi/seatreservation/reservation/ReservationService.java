package com.rishi.seatreservation.reservation;

import com.rishi.seatreservation.common.ApiException;
import com.rishi.seatreservation.common.SqlErrors;
import com.rishi.seatreservation.show.ShowRepository;
import com.rishi.seatreservation.show.ShowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Reserve and cancel-reservation. Each runs as ONE database transaction that takes locks in the
 * global order: show -> reservation -> user_quota -> seats (sorted). See CONCURRENCY.md.
 */
@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);
    private static final int MAX_KEY_LENGTH = 128;

    private final ReservationRepository reservations;
    private final ShowRepository shows;
    private final TransactionTemplate tx;
    private final ReserveLimiter limiter;
    private final TakenSeatCache takenSeats;

    public ReservationService(ReservationRepository reservations, ShowRepository shows, TransactionTemplate tx,
                              ReserveLimiter limiter, TakenSeatCache takenSeats) {
        this.reservations = reservations;
        this.shows = shows;
        this.tx = tx;
        this.limiter = limiter;
        this.takenSeats = takenSeats;
    }

    public ReserveResult reserve(final UUID showId, final UUID userId, ReserveRequest body, String headerKey) {
        final String key = resolveKey(body, headerKey);
        final List<String> seats = validateSeats(body);
        final String hash = requestHash(showId, seats);

        if (!limiter.tryAcquire()) {
            logOutcome("rate_limited", showId, seats, null);
            throw ApiException.rateLimited("Too many requests in flight, please retry");
        }
        try {
            // Fast path for hot seats: decline from memory. A retry of the user's own successful
            // request must still be replayed, so check their key first (cheap indexed read, no locks).
            // Plain ApiException (not SeatTakenException) so a memory decline never extends the TTL:
            // only the database can (re)confirm that a seat is taken.
            if (takenSeats.anyTaken(showId, seats) && reservations.findByUserAndKey(userId, key) == null) {
                throw ApiException.conflict("seat_taken", "Seat(s) already taken: " + String.join(", ", seats));
            }
            ReserveResult result = runWithDeadlockRetry(
                    status -> reserveInTransaction(showId, userId, key, hash, seats));
            if (!result.isReplayed()) {
                takenSeats.markTaken(showId, seats);
            }
            logOutcome(result.isReplayed() ? "idempotent_replay" : "confirmed", showId, seats,
                    result.getReservation().getReservationId());
            return result;
        } catch (SeatTakenException e) {
            takenSeats.markTaken(showId, e.getTakenSeats());
            logOutcome("seat_taken", showId, seats, null);
            throw e;
        } catch (ApiException e) {
            logOutcome(e.getCode(), showId, seats, null);
            throw e;
        } finally {
            limiter.release();
        }
    }

    private ReserveResult reserveInTransaction(UUID showId, UUID userId, String key, String hash,
                                               List<String> seats) {
        // (1) Shared lock on the show: blocks a concurrent cancel-show until we commit.
        ShowRepository.ShowRow show = shows.lockForShare(showId);
        if (show == null) {
            throw ApiException.notFound("show_not_found", "Show not found");
        }
        if (show.isCancelled()) {
            ReservationRepository.ReservationRow existing = reservations.findByUserAndKey(userId, key);
            if (existing != null) {
                return replayOrMismatch(existing, hash);
            }
            throw ApiException.conflict("show_cancelled", "Show is cancelled");
        }
        if (seats.size() > show.perUserLimit) {
            throw ApiException.invalid("At most " + show.perUserLimit + " seats per request");
        }

        // (2) Idempotency: insert the reservation unless this key was already used by this user.
        long amount = Math.multiplyExact(show.pricePaise, (long) seats.size());
        ReservationRepository.ReservationRow created = reservations.insertIfKeyUnused(
                UUID.randomUUID(), showId, userId, key, hash, seats, amount);
        if (created == null) {
            ReservationRepository.ReservationRow existing = reservations.findByUserAndKey(userId, key);
            if (existing == null) {
                throw ApiException.rateLimited("Concurrent request with the same key, please retry");
            }
            return replayOrMismatch(existing, hash);
        }

        // (3) Per-user limit: conditional increment on the user's quota row.
        if (reservations.addToQuota(showId, userId, seats.size(), show.perUserLimit) == null) {
            throw ApiException.conflict("per_user_limit",
                    "Would exceed the limit of " + show.perUserLimit + " seats per user for this show");
        }

        // (4) Seats: lock in sorted order, then confirm only if every seat is still available.
        List<ReservationRepository.SeatState> locked = reservations.lockSeats(showId, seats);
        if (locked.size() != seats.size()) {
            Set<String> found = new HashSet<String>();
            for (ReservationRepository.SeatState s : locked) {
                found.add(s.label);
            }
            List<String> unknown = new ArrayList<String>();
            for (String label : seats) {
                if (!found.contains(label)) {
                    unknown.add(label);
                }
            }
            throw ApiException.badRequest("unknown_seat", "Seat(s) not in this show: " + String.join(", ", unknown));
        }
        List<String> taken = new ArrayList<String>();
        for (ReservationRepository.SeatState s : locked) {
            if (!"available".equals(s.state)) {
                taken.add(s.label);
            }
        }
        if (!taken.isEmpty()) {
            throw new SeatTakenException(taken);
        }
        if (reservations.confirmSeats(showId, seats, created.id) != seats.size()) {
            // Cannot happen while we hold the row locks; kept as a guard.
            throw new SeatTakenException(seats);
        }
        return ReserveResult.created(new ReservationResponse(created));
    }

    private static ReserveResult replayOrMismatch(ReservationRepository.ReservationRow existing, String hash) {
        if (existing.requestHash.equals(hash)) {
            return ReserveResult.replayed(new ReservationResponse(existing));
        }
        throw ApiException.conflict("idempotency_key_mismatch",
                "This idempotency key was already used with a different request");
    }

    public ReservationResponse cancel(final UUID reservationId, final UUID userId) {
        if (!limiter.tryAcquire()) {
            throw ApiException.rateLimited("Too many requests in flight, please retry");
        }
        final boolean[] releasedNow = new boolean[1];
        try {
            ReservationResponse response = runWithDeadlockRetry(status -> {
                releasedNow[0] = false;
                ReservationRepository.ReservationRow owned = reservations.findOwned(reservationId, userId);
                if (owned == null) {
                    throw ApiException.notFound("reservation_not_found", "Reservation not found");
                }
                // Same lock order as reserve: show -> reservation -> user_quota -> seats.
                shows.lockForShare(owned.showId);
                ReservationRepository.ReservationRow row = reservations.lockForUpdate(reservationId);
                if (!row.isConfirmed()) {
                    return new ReservationResponse(row); // already cancelled: nothing to do
                }
                reservations.subtractFromQuota(row.showId, userId, row.seats.size());
                reservations.releaseSeats(reservationId);
                releasedNow[0] = true;
                return new ReservationResponse(reservations.markCancelled(reservationId));
            });
            if (releasedNow[0]) {
                takenSeats.release(response.getShowId(), response.getSeats());
            }
            log.atInfo().addKeyValue("reservation_id", reservationId)
                    .addKeyValue("outcome", releasedNow[0] ? "cancelled" : "already_cancelled")
                    .log("cancel reservation");
            return response;
        } finally {
            limiter.release();
        }
    }

    /** Deadlocks should not happen given the lock order; as a safety net, retry once, then 429. */
    private <T> T runWithDeadlockRetry(TransactionCallback<T> work) {
        try {
            return tx.execute(work);
        } catch (DataAccessException e) {
            if (!SqlErrors.isDeadlock(e)) {
                throw e;
            }
            log.warn("deadlock detected, retrying once");
            return tx.execute(work);
        }
    }

    private static String resolveKey(ReserveRequest body, String headerKey) {
        String bodyKey = body == null ? null : body.getIdempotencyKey();
        if (bodyKey != null && headerKey != null && !bodyKey.equals(headerKey)) {
            throw ApiException.invalid("Idempotency-Key header and idempotency_key body field differ");
        }
        String key = bodyKey != null ? bodyKey : headerKey;
        if (key == null || key.trim().isEmpty() || key.length() > MAX_KEY_LENGTH) {
            throw ApiException.invalid("idempotency_key is required (1-128 characters)");
        }
        return key;
    }

    /** Returns the seats sorted (the lock order) after checking format and duplicates. */
    private static List<String> validateSeats(ReserveRequest body) {
        if (body == null || body.getSeats() == null || body.getSeats().isEmpty()) {
            throw ApiException.invalid("seats must contain at least one seat");
        }
        Set<String> unique = new HashSet<String>();
        for (String label : body.getSeats()) {
            if (label == null || !ShowService.SEAT_LABEL.matcher(label).matches()) {
                throw ApiException.invalid("Invalid seat label: " + label);
            }
            if (!unique.add(label)) {
                throw ApiException.invalid("Duplicate seat label: " + label);
            }
        }
        List<String> sorted = new ArrayList<String>(unique);
        Collections.sort(sorted);
        return sorted;
    }

    /** Same show + same set of seats (order-insensitive) = same request. */
    private static String requestHash(UUID showId, List<String> sortedSeats) {
        String canonical = showId + "|" + String.join(",", sortedSeats);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void logOutcome(String outcome, UUID showId, List<String> seats, UUID reservationId) {
        log.atInfo()
                .addKeyValue("outcome", outcome)
                .addKeyValue("show_id", showId)
                .addKeyValue("seats", String.join(",", seats))
                .addKeyValue("reservation_id", reservationId)
                .log("reserve");
    }
}
