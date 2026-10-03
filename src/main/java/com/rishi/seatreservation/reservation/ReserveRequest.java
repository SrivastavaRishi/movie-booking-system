package com.rishi.seatreservation.reservation;

import java.util.List;

/** Body of POST /shows/{id}/reserve. Any "user_id" field is ignored: identity comes from the token. */
public class ReserveRequest {

    private List<String> seats;
    private String idempotencyKey;

    public List<String> getSeats() {
        return seats;
    }

    public void setSeats(List<String> seats) {
        this.seats = seats;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }
}
