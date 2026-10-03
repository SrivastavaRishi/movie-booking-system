package com.rishi.seatreservation.show;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.UUID;

@JsonPropertyOrder({"id", "status", "reservations_cancelled", "seats_released"})
public class CancelShowResponse {

    private final UUID id;
    private final String status = "cancelled";
    private final int reservationsCancelled;
    private final int seatsReleased;

    public CancelShowResponse(UUID id, int reservationsCancelled, int seatsReleased) {
        this.id = id;
        this.reservationsCancelled = reservationsCancelled;
        this.seatsReleased = seatsReleased;
    }

    public UUID getId() {
        return id;
    }

    public String getStatus() {
        return status;
    }

    public int getReservationsCancelled() {
        return reservationsCancelled;
    }

    public int getSeatsReleased() {
        return seatsReleased;
    }
}
