package com.rishi.seatreservation.reservation;

import com.rishi.seatreservation.common.ApiException;
import org.springframework.http.HttpStatus;

import java.util.List;

/** 409 seat_taken, carrying which seats were taken so they can be remembered in TakenSeatCache. */
public class SeatTakenException extends ApiException {

    private final List<String> takenSeats;

    public SeatTakenException(List<String> takenSeats) {
        super(HttpStatus.CONFLICT, "seat_taken", message(takenSeats));
        this.takenSeats = takenSeats;
    }

    public List<String> getTakenSeats() {
        return takenSeats;
    }

    private static String message(List<String> seats) {
        if (seats.size() == 1) {
            return "Seat " + seats.get(0) + " is already taken";
        }
        return "Seats " + String.join(", ", seats) + " are already taken";
    }
}
