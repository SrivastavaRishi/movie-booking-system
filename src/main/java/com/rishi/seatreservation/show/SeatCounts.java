package com.rishi.seatreservation.show;

import java.util.List;

public class SeatCounts {

    private final int available;
    private final int held;
    private final int confirmed;

    public SeatCounts(int available, int held, int confirmed) {
        this.available = available;
        this.held = held;
        this.confirmed = confirmed;
    }

    /** Counts derived from the same seat list that is returned, so they always add up to the total. */
    public static SeatCounts of(List<SeatView> seats) {
        int available = 0;
        int held = 0;
        int confirmed = 0;
        for (SeatView seat : seats) {
            if ("available".equals(seat.getStatus())) {
                available++;
            } else if ("held".equals(seat.getStatus())) {
                held++;
            } else {
                confirmed++;
            }
        }
        return new SeatCounts(available, held, confirmed);
    }

    public int getAvailable() {
        return available;
    }

    public int getHeld() {
        return held;
    }

    public int getConfirmed() {
        return confirmed;
    }
}
