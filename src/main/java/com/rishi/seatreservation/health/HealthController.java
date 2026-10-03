package com.rishi.seatreservation.health;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/health")
public class HealthController {

    private static final int CHECK_TIMEOUT_SECONDS = 2;

    /**
     * A dedicated one-connection pool for the readiness check, separate from the main pool: during a
     * burst the main pool is busy by design, and readiness must not report DOWN just because of that.
     */
    private final HikariDataSource probe;

    public HealthController(@Value("${spring.datasource.url}") String url,
                            @Value("${spring.datasource.username}") String user,
                            @Value("${spring.datasource.password}") String password) {
        probe = new HikariDataSource();
        probe.setJdbcUrl(url);
        probe.setUsername(user);
        probe.setPassword(password);
        probe.setPoolName("readiness-probe");
        probe.setMaximumPoolSize(1);
        probe.setMinimumIdle(0);
        probe.setConnectionTimeout(CHECK_TIMEOUT_SECONDS * 1000L);
        probe.setInitializationFailTimeout(-1); // do not fail startup; just report DOWN
    }

    /** Liveness: no dependency checks. If we can answer, the process is alive. */
    @GetMapping("/live")
    public Map<String, String> live() {
        return Collections.singletonMap("status", "UP");
    }

    /** Readiness: is the database reachable? Fails closed (503) if not. */
    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        boolean dbUp = checkDatabase();
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("status", dbUp ? "UP" : "DOWN");
        body.put("checks", Collections.singletonMap("db", dbUp ? "UP" : "DOWN"));
        return ResponseEntity.status(dbUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    private boolean checkDatabase() {
        try (Connection connection = probe.getConnection();
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(CHECK_TIMEOUT_SECONDS);
            statement.execute("SELECT 1");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @PreDestroy
    public void close() {
        probe.close();
    }
}
