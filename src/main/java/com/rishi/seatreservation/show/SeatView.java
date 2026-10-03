package com.rishi.seatreservation.show;

public class SeatView {

    private final String label;
    private final String status;

    public SeatView(String label, String status) {
        this.label = label;
        this.status = status;
    }

    public String getLabel() {
        return label;
    }

    public String getStatus() {
        return status;
    }
}
