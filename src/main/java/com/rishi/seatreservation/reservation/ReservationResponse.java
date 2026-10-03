package com.rishi.seatreservation.reservation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class ReservationResponse {

    private final UUID reservationId;
    private final UUID showId;
    private final UUID userId;
    private final List<String> seats;
    private final long amountPaise;
    private final String status;
    private final Instant createdAt;
    private final Instant cancelledAt;

    public ReservationResponse(ReservationRepository.ReservationRow row) {
        this.reservationId = row.id;
        this.showId = row.showId;
        this.userId = row.userId;
        this.seats = row.seats;
        this.amountPaise = row.amountPaise;
        this.status = row.status;
        this.createdAt = row.createdAt;
        this.cancelledAt = row.cancelledAt;
    }

    public UUID getReservationId() {
        return reservationId;
    }

    public UUID getShowId() {
        return showId;
    }

    public UUID getUserId() {
        return userId;
    }

    public List<String> getSeats() {
        return seats;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }
}
