package com.rishi.seatreservation.reservation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Caps how many reserve/cancel requests work inside the database at once (sized to the DB pool).
 * Extra requests wait up to queue-timeout-ms for a slot, then get a clean 429 instead of piling up
 * until something times out with a 500.
 */
@Component
public class ReserveLimiter {

    private final Semaphore permits;
    private final long queueTimeoutMs;

    public ReserveLimiter(@Value("${app.reserve.max-inflight}") int maxInflight,
                          @Value("${app.reserve.queue-timeout-ms}") long queueTimeoutMs) {
        this.permits = new Semaphore(maxInflight);
        this.queueTimeoutMs = queueTimeoutMs;
    }

    public boolean tryAcquire() {
        try {
            return permits.tryAcquire(queueTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void release() {
        permits.release();
    }
}
