package com.rishi.seatreservation.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Business counters exposed at /metrics. Counters are incremented only once the outcome is final
 * (after commit for confirmations), so they reconcile with what the API returned.
 *
 * Prometheus names:
 *   reservations_confirmed_total
 *   reservation_seats_confirmed_total
 *   reservations_declined_total{reason="..."}
 *   reservations_cancelled_total{by="user|admin"}
 *   reservation_seats_released_total
 */
@Component
public class ReservationMetrics {

    private final MeterRegistry registry;
    private final Counter confirmed;
    private final Counter seatsConfirmed;
    private final Counter seatsReleased;

    public ReservationMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.confirmed = Counter.builder("reservations.confirmed")
                .description("Reservations newly confirmed (HTTP 201)")
                .register(registry);
        this.seatsConfirmed = Counter.builder("reservation.seats.confirmed")
                .description("Seats confirmed by new reservations")
                .register(registry);
        this.seatsReleased = Counter.builder("reservation.seats.released")
                .description("Seats returned to available by user or admin cancels")
                .register(registry);
    }

    public void confirmed(int seats) {
        confirmed.increment();
        seatsConfirmed.increment(seats);
    }

    /** reason: seat_taken, per_user_limit, idempotent_replay, idempotency_key_mismatch, ... */
    public void declined(String reason) {
        Counter.builder("reservations.declined")
                .description("Reserve requests that did not create a reservation, by reason")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    /** by: "user" (owner cancel) or "admin" (show cancel). */
    public void cancelled(String by, int reservations, int seats) {
        Counter.builder("reservations.cancelled")
                .description("Reservations cancelled, by who cancelled them")
                .tag("by", by)
                .register(registry)
                .increment(reservations);
        seatsReleased.increment(seats);
    }
}
