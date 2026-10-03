package com.rishi.seatreservation.reservation;

/** A reservation plus whether it was newly created (201) or replayed for a repeated key (200). */
public class ReserveResult {

    private final ReservationResponse reservation;
    private final boolean replayed;

    private ReserveResult(ReservationResponse reservation, boolean replayed) {
        this.reservation = reservation;
        this.replayed = replayed;
    }

    public static ReserveResult created(ReservationResponse reservation) {
        return new ReserveResult(reservation, false);
    }

    public static ReserveResult replayed(ReservationResponse reservation) {
        return new ReserveResult(reservation, true);
    }

    public ReservationResponse getReservation() {
        return reservation;
    }

    public boolean isReplayed() {
        return replayed;
    }
}
