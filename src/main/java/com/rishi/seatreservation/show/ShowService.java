package com.rishi.seatreservation.show;

import com.rishi.seatreservation.common.ApiException;
import com.rishi.seatreservation.metrics.ReservationMetrics;
import com.rishi.seatreservation.metrics.SeatGauges;
import com.rishi.seatreservation.reservation.TakenSeatCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class ShowService {

    private static final Logger log = LoggerFactory.getLogger(ShowService.class);

    public static final Pattern SEAT_LABEL = Pattern.compile("[A-Za-z0-9-]{1,16}");
    private static final int MAX_NAME = 200;
    private static final int MAX_SEATS = 10_000;
    private static final int DEFAULT_PER_USER_LIMIT = 4;

    private final ShowRepository shows;
    private final TransactionTemplate tx;
    private final TakenSeatCache takenSeats;
    private final SeatGauges seatGauges;
    private final ReservationMetrics metrics;

    public ShowService(ShowRepository shows, TransactionTemplate tx, TakenSeatCache takenSeats,
                       SeatGauges seatGauges, ReservationMetrics metrics) {
        this.shows = shows;
        this.tx = tx;
        this.takenSeats = takenSeats;
        this.seatGauges = seatGauges;
        this.metrics = metrics;
    }

    public ShowResponse create(CreateShowRequest req) {
        if (req == null) {
            throw ApiException.invalid("Request body is required");
        }
        final String name = req.getName() == null ? "" : req.getName().trim();
        if (name.isEmpty() || name.length() > MAX_NAME) {
            throw ApiException.invalid("name must be 1-200 characters");
        }
        final List<String> seats = req.getSeats();
        if (seats == null || seats.isEmpty() || seats.size() > MAX_SEATS) {
            throw ApiException.invalid("seats must contain 1-10000 labels");
        }
        Set<String> unique = new HashSet<String>();
        for (String label : seats) {
            if (label == null || !SEAT_LABEL.matcher(label).matches()) {
                throw ApiException.invalid("Invalid seat label: " + label
                        + " (1-16 chars, letters, digits or '-')");
            }
            if (!unique.add(label)) {
                throw ApiException.invalid("Duplicate seat label: " + label);
            }
        }
        if (req.getPricePaise() == null || req.getPricePaise() <= 0) {
            throw ApiException.invalid("price_paise must be a positive integer");
        }
        final long price = req.getPricePaise();
        final int limit = req.getPerUserLimit() == null ? DEFAULT_PER_USER_LIMIT : req.getPerUserLimit();
        if (limit < 1) {
            throw ApiException.invalid("per_user_limit must be at least 1");
        }

        final UUID id = UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            shows.insertShow(id, name, price, limit, seats.size());
            shows.insertSeats(id, seats.toArray(new String[0]));
        });
        seatGauges.register(id);
        log.atInfo().addKeyValue("show_id", id).addKeyValue("total_seats", seats.size()).log("show created");
        return get(id);
    }

    public ShowResponse get(UUID id) {
        ShowRepository.ShowRow show = shows.find(id);
        if (show == null) {
            throw ApiException.notFound("show_not_found", "Show not found");
        }
        return new ShowResponse(show, shows.findSeats(id));
    }

    /**
     * Lock order: show (exclusive) -> reservations -> user_quota -> seats. The exclusive show lock waits
     * for every in-flight reserve (they hold a shared lock on it) and blocks new ones until we commit.
     */
    public CancelShowResponse cancel(final UUID id) {
        CancelShowResponse result = tx.execute(status -> {
            ShowRepository.ShowRow show = shows.lockForUpdate(id);
            if (show == null) {
                throw ApiException.notFound("show_not_found", "Show not found");
            }
            if (show.isCancelled()) {
                return new CancelShowResponse(id, 0, 0);
            }
            shows.markCancelled(id);
            int reservations = shows.cancelReservationsOfShow(id);
            shows.resetQuotas(id);
            int seats = shows.releaseAllSeats(id);
            return new CancelShowResponse(id, reservations, seats);
        });
        takenSeats.clearShow(id);
        seatGauges.unregister(id);
        if (result.getReservationsCancelled() > 0 || result.getSeatsReleased() > 0) {
            metrics.cancelled("admin", result.getReservationsCancelled(), result.getSeatsReleased());
        }
        log.atInfo().addKeyValue("show_id", id)
                .addKeyValue("reservations_cancelled", result.getReservationsCancelled())
                .addKeyValue("seats_released", result.getSeatsReleased())
                .log("show cancelled");
        return result;
    }
}
