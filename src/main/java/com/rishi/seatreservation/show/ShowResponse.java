package com.rishi.seatreservation.show;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonPropertyOrder({"id", "name", "status", "price_paise", "per_user_limit", "total_seats", "counts", "seats", "created_at", "cancelled_at"})
public class ShowResponse {

    private final UUID id;
    private final String name;
    private final String status;
    private final long pricePaise;
    private final int perUserLimit;
    private final int totalSeats;
    private final SeatCounts counts;
    private final List<SeatView> seats;
    private final Instant createdAt;
    private final Instant cancelledAt;

    public ShowResponse(ShowRepository.ShowRow show, List<SeatView> seats) {
        this.id = show.id;
        this.name = show.name;
        this.status = show.status;
        this.pricePaise = show.pricePaise;
        this.perUserLimit = show.perUserLimit;
        this.totalSeats = show.totalSeats;
        this.counts = SeatCounts.of(seats);
        this.seats = seats;
        this.createdAt = show.createdAt;
        this.cancelledAt = show.cancelledAt;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getStatus() {
        return status;
    }

    public long getPricePaise() {
        return pricePaise;
    }

    public int getPerUserLimit() {
        return perUserLimit;
    }

    public int getTotalSeats() {
        return totalSeats;
    }

    public SeatCounts getCounts() {
        return counts;
    }

    public List<SeatView> getSeats() {
        return seats;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }
}
