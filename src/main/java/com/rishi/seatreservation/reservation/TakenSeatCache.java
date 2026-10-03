package com.rishi.seatreservation.reservation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory "this seat was recently seen taken" set, so a hot-seat storm is declined without
 * touching the database.
 *
 * It is only ever used to REJECT, never to confirm: the database alone decides who gets a seat, so a
 * stale entry can never cause a double-sell. Entries expire after a short TTL, so the worst case is a
 * just-released seat being declined for up to that TTL. Valid for a single instance only.
 */
@Component
public class TakenSeatCache {

    private final ConcurrentHashMap<String, Long> expiresAt = new ConcurrentHashMap<String, Long>();
    private final long ttlMs;

    public TakenSeatCache(@Value("${app.reserve.taken-cache-ttl-ms}") long ttlMs) {
        this.ttlMs = ttlMs;
    }

    public boolean anyTaken(UUID showId, Collection<String> labels) {
        if (ttlMs <= 0) {
            return false;
        }
        long now = System.currentTimeMillis();
        for (String label : labels) {
            Long expiry = expiresAt.get(key(showId, label));
            if (expiry != null && expiry > now) {
                return true;
            }
        }
        return false;
    }

    public void markTaken(UUID showId, Collection<String> labels) {
        if (ttlMs <= 0) {
            return;
        }
        long expiry = System.currentTimeMillis() + ttlMs;
        for (String label : labels) {
            expiresAt.put(key(showId, label), expiry);
        }
    }

    public void release(UUID showId, Collection<String> labels) {
        for (String label : labels) {
            expiresAt.remove(key(showId, label));
        }
    }

    public void clearShow(UUID showId) {
        String prefix = showId + ":";
        Iterator<String> it = expiresAt.keySet().iterator();
        while (it.hasNext()) {
            if (it.next().startsWith(prefix)) {
                it.remove();
            }
        }
    }

    /** Drops expired entries so the map does not grow without bound. */
    @Scheduled(fixedDelay = 30_000)
    public void purgeExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> it = expiresAt.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() <= now) {
                it.remove();
            }
        }
    }

    private static String key(UUID showId, String label) {
        return showId + ":" + label;
    }
}
