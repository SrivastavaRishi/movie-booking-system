package com.rishi.seatreservation.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gauge seats{show_id, state} for every active show, read from the DATABASE at scrape time, so it
 * always matches GET /shows/{id} (same source of truth, nothing to drift).
 *
 * One GROUP BY query serves all gauges of a scrape: the result is cached for 100 ms, long enough to
 * cover one scrape (which reads every gauge within a few ms) but short enough to stay live.
 */
@Component
public class SeatGauges {

    private static final Logger log = LoggerFactory.getLogger(SeatGauges.class);
    private static final String[] STATES = {"available", "held", "confirmed"};
    private static final long SNAPSHOT_TTL_MS = 100;

    private final MeterRegistry registry;
    private final JdbcTemplate jdbc;
    private final Map<UUID, List<Meter>> gaugesByShow = new ConcurrentHashMap<UUID, List<Meter>>();

    private Map<String, Long> snapshot = new HashMap<String, Long>();
    private long snapshotAt;
    private boolean snapshotOk;

    public SeatGauges(MeterRegistry registry, JdbcTemplate jdbc) {
        this.registry = registry;
        this.jdbc = jdbc;
    }

    /** Shows created before this process started still get gauges. */
    @EventListener(ApplicationReadyEvent.class)
    public void registerActiveShows() {
        List<UUID> ids = jdbc.query("SELECT id FROM shows WHERE status = 'active'",
                (rs, i) -> rs.getObject("id", UUID.class));
        for (UUID id : ids) {
            register(id);
        }
    }

    public void register(final UUID showId) {
        if (gaugesByShow.containsKey(showId)) {
            return;
        }
        List<Meter> meters = new ArrayList<Meter>();
        for (final String state : STATES) {
            meters.add(Gauge.builder("seats", this, g -> g.value(showId, state))
                    .description("Seats per show and state, read from the database at scrape time")
                    .tag("show_id", showId.toString())
                    .tag("state", state)
                    .register(registry));
        }
        gaugesByShow.put(showId, meters);
    }

    /** Cancelled shows stop being reported, keeping the number of series bounded. */
    public void unregister(UUID showId) {
        List<Meter> meters = gaugesByShow.remove(showId);
        if (meters != null) {
            for (Meter meter : meters) {
                registry.remove(meter);
            }
        }
    }

    private synchronized double value(UUID showId, String state) {
        long now = System.currentTimeMillis();
        if (now - snapshotAt > SNAPSHOT_TTL_MS) {
            refresh();
            snapshotAt = now;
        }
        if (!snapshotOk) {
            return Double.NaN; // DB unreachable: report "unknown" rather than a wrong number
        }
        Long count = snapshot.get(showId + ":" + state);
        return count == null ? 0 : count;
    }

    private void refresh() {
        try {
            final Map<String, Long> fresh = new HashMap<String, Long>();
            jdbc.query("SELECT s.show_id, s.state, count(*) AS n FROM seats s "
                            + "JOIN shows sh ON sh.id = s.show_id WHERE sh.status = 'active' "
                            + "GROUP BY s.show_id, s.state",
                    rs -> {
                        fresh.put(rs.getObject("show_id", UUID.class) + ":" + rs.getString("state"), rs.getLong("n"));
                    });
            snapshot = fresh;
            snapshotOk = true;
        } catch (RuntimeException e) {
            log.warn("could not read seat counts for metrics: {}", e.getMessage());
            snapshotOk = false;
        }
    }
}
