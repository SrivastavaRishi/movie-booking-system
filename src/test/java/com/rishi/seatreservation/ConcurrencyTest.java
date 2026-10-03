package com.rishi.seatreservation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fires real concurrent HTTP requests at the running app backed by a real PostgreSQL, so the
 * database locks are exercised exactly as in production. A CountDownLatch releases all threads at
 * the same instant.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ConcurrencyTest {

    private static final String ADMIN = "admin@seatbooking.local";
    private static final String ADMIN_PASSWORD = "Admin@12345";

    @Autowired
    private TestRestTemplate http;

    private final ObjectMapper json = new ObjectMapper();
    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        adminToken = token(ADMIN, ADMIN_PASSWORD);
    }

    @Test
    void hotSeat_exactlyOneWinner_restGetCleanConflict() throws Exception {
        String showId = createShow(seatLabels(10), 4);
        List<String> users = newUsers(300);

        List<Integer> statuses = runConcurrently(users.size(), i ->
                reserve(users.get(i), showId, Collections.singletonList("S1"), "key-" + i).getStatusCode().value());

        assertEquals(1, count(statuses, 201), "exactly one winner");
        assertEquals(users.size() - 1, count(statuses, 409), "everyone else gets 409");
        assertReconciles(showId, 10);
        assertEquals(1, show(showId).get("counts").get("confirmed").asInt());
    }

    @Test
    void perUserLimit_holdsUnderParallelRequests() throws Exception {
        String showId = createShow(seatLabels(20), 4);
        String user = newUsers(1).get(0);

        List<Integer> statuses = runConcurrently(10, i ->
                reserve(user, showId, Collections.singletonList("S" + (i + 1)), "k" + i).getStatusCode().value());

        assertEquals(4, count(statuses, 201), "at most per_user_limit seats");
        assertEquals(6, count(statuses, 409));
        assertEquals(4, show(showId).get("counts").get("confirmed").asInt());
        assertReconciles(showId, 20);
    }

    @Test
    void sameKeyRetries_reserveExactlyOnce() throws Exception {
        String showId = createShow(seatLabels(10), 4);
        String user = newUsers(1).get(0);
        final Set<String> reservationIds = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

        List<Integer> statuses = runConcurrently(50, i -> {
            ResponseEntity<String> r = reserve(user, showId, Arrays.asList("S1", "S2"), "same-key");
            if (r.getStatusCode().is2xxSuccessful()) {
                reservationIds.add(json.readTree(r.getBody()).get("reservation_id").asText());
            }
            return r.getStatusCode().value();
        });

        assertEquals(1, count(statuses, 201), "one new reservation");
        assertEquals(49, count(statuses, 200), "the rest are replays");
        assertEquals(1, reservationIds.size(), "all replays return the same reservation");
        assertEquals(2, show(showId).get("counts").get("confirmed").asInt());

        ResponseEntity<String> mismatch = reserve(user, showId, Collections.singletonList("S3"), "same-key");
        assertEquals(409, mismatch.getStatusCode().value());
        assertEquals("idempotency_key_mismatch", json.readTree(mismatch.getBody()).get("error").asText());
        assertReconciles(showId, 10);
    }

    @Test
    void overlappingMultiSeatRequests_noDeadlock_allOrNothing() throws Exception {
        String showId = createShow(seatLabels(4), 4);
        List<String> users = newUsers(100);

        // Half ask for [S1,S2], half for [S2,S1]: opposite orders would deadlock without sorted locking.
        List<Integer> statuses = runConcurrently(users.size(), i -> reserve(users.get(i), showId,
                i % 2 == 0 ? Arrays.asList("S1", "S2") : Arrays.asList("S2", "S1"), "k").getStatusCode().value());

        assertEquals(1, count(statuses, 201));
        assertEquals(users.size() - 1, count(statuses, 409));
        assertEquals(0, count5xx(statuses));
        assertEquals(2, show(showId).get("counts").get("confirmed").asInt());
        assertReconciles(showId, 4);
    }

    @Test
    void reservesRacingShowCancel_noSeatConfirmedOnCancelledShow() throws Exception {
        String showId = createShow(seatLabels(200), 4);
        List<String> users = newUsers(100);

        List<Integer> statuses = runConcurrently(users.size() + 1, i -> {
            if (i == users.size()) {
                return exchange(HttpMethod.POST, "/shows/" + showId + "/cancel", adminToken, null)
                        .getStatusCode().value();
            }
            return reserve(users.get(i), showId, Collections.singletonList("S" + (i + 1)), "k")
                    .getStatusCode().value();
        });

        assertEquals(0, count5xx(statuses));
        JsonNode show = show(showId);
        assertEquals("cancelled", show.get("status").asText());
        assertEquals(0, show.get("counts").get("confirmed").asInt(), "nothing confirmed after cancel");
        assertReconciles(showId, 200);
    }

    @Test
    void concurrentCancels_releaseOnce_andSeatIsRebookable() throws Exception {
        String showId = createShow(seatLabels(5), 2);
        List<String> users = newUsers(2);
        String owner = users.get(0);
        ResponseEntity<String> created = reserve(owner, showId, Arrays.asList("S1", "S2"), "k");
        final String reservationId = json.readTree(created.getBody()).get("reservation_id").asText();

        List<Integer> statuses = runConcurrently(20, i ->
                exchange(HttpMethod.POST, "/reservations/" + reservationId + "/cancel", tokenFor(owner), null)
                        .getStatusCode().value());
        assertEquals(20, count(statuses, 200));
        assertEquals(0, show(showId).get("counts").get("confirmed").asInt());

        // Quota was decremented exactly once: the owner can book their full limit (2) again.
        assertEquals(201, reserve(owner, showId, Arrays.asList("S3", "S4"), "k2").getStatusCode().value());
        // The released seats are cleanly re-bookable by someone else.
        assertEquals(201, reserve(users.get(1), showId, Arrays.asList("S1", "S2"), "k").getStatusCode().value());
        assertReconciles(showId, 5);
    }

    @Test
    void identityComesFromToken_notBody_andOnlyOwnerCancels() throws Exception {
        String showId = createShow(seatLabels(5), 4);
        List<String> users = newUsers(2);
        String alice = users.get(0);
        String bob = users.get(1);

        String body = "{\"seats\":[\"S1\"],\"idempotency_key\":\"k\",\"user_id\":\"" + userIds.get(bob) + "\"}";
        ResponseEntity<String> r = exchange(HttpMethod.POST, "/shows/" + showId + "/reserve", tokenFor(alice), body);
        JsonNode reservation = json.readTree(r.getBody());
        assertEquals(userIds.get(alice), reservation.get("user_id").asText(), "spoofed user_id in body is ignored");

        ResponseEntity<String> bobCancel = exchange(HttpMethod.POST,
                "/reservations/" + reservation.get("reservation_id").asText() + "/cancel", tokenFor(bob), null);
        assertEquals(404, bobCancel.getStatusCode().value(), "cannot cancel someone else's reservation");
        assertEquals(1, show(showId).get("counts").get("confirmed").asInt());
    }

    // ---------------------------------------------------------------- helpers

    private interface IndexedTask {
        int run(int i) throws Exception;
    }

    private List<Integer> runConcurrently(int n, final IndexedTask task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        final CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<Future<Integer>>();
            for (int i = 0; i < n; i++) {
                final int index = i;
                futures.add(pool.submit(new Callable<Integer>() {
                    @Override
                    public Integer call() throws Exception {
                        start.await();
                        return task.run(index);
                    }
                }));
            }
            start.countDown();
            List<Integer> results = new ArrayList<Integer>();
            for (Future<Integer> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private final Map<String, String> tokens = new ConcurrentHashMap<String, String>();
    private final Map<String, String> userIds = new ConcurrentHashMap<String, String>();

    private List<String> newUsers(int n) throws Exception {
        final String run = UUID.randomUUID().toString().substring(0, 8);
        runConcurrently(Math.min(n, 50), i -> {
            for (int u = i; u < n; u += Math.min(n, 50)) {
                String email = "u" + u + "-" + run + "@test.local";
                ResponseEntity<String> registered = post("/auth/register", null,
                        "{\"email\":\"" + email + "\",\"password\":\"password1\"}");
                userIds.put(email, json.readTree(registered.getBody()).get("user_id").asText());
                tokens.put(email, token(email, "password1"));
            }
            return 0;
        });
        List<String> users = new ArrayList<String>();
        for (int u = 0; u < n; u++) {
            users.add("u" + u + "-" + run + "@test.local");
        }
        return users;
    }

    private String tokenFor(String user) {
        return tokens.get(user);
    }

    private String token(String user, String password) throws Exception {
        ResponseEntity<String> r = post("/auth/token", null,
                "{\"email\":\"" + user + "\",\"password\":\"" + password + "\"}");
        return json.readTree(r.getBody()).get("token").asText();
    }

    private String createShow(List<String> seats, int limit) throws Exception {
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("name", "test-show");
        body.put("seats", seats);
        body.put("price_paise", 25000);
        body.put("per_user_limit", limit);
        ResponseEntity<String> r = post("/shows", adminToken, json.writeValueAsString(body));
        assertEquals(201, r.getStatusCode().value(), r.getBody());
        return json.readTree(r.getBody()).get("id").asText();
    }

    private ResponseEntity<String> reserve(String user, String showId, List<String> seats, String key)
            throws Exception {
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("seats", seats);
        body.put("idempotency_key", key);
        return post("/shows/" + showId + "/reserve", tokenFor(user), json.writeValueAsString(body));
    }

    private JsonNode show(String showId) throws Exception {
        return json.readTree(exchange(HttpMethod.GET, "/shows/" + showId, null, null).getBody());
    }

    private void assertReconciles(String showId, int total) throws Exception {
        JsonNode counts = show(showId).get("counts");
        int sum = counts.get("available").asInt() + counts.get("held").asInt() + counts.get("confirmed").asInt();
        assertEquals(total, sum, "available + held + confirmed == total_seats");
    }

    private ResponseEntity<String> post(String path, String token, String body) {
        return exchange(HttpMethod.POST, path, token, body);
    }

    private ResponseEntity<String> exchange(HttpMethod method, String path, String token, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return http.exchange(path, method, new HttpEntity<String>(body, headers), String.class);
    }

    private static List<String> seatLabels(int n) {
        List<String> seats = new ArrayList<String>();
        for (int i = 1; i <= n; i++) {
            seats.add("S" + i);
        }
        return seats;
    }

    private static int count(List<Integer> statuses, int status) {
        int c = 0;
        for (Integer s : statuses) {
            if (s == status) {
                c++;
            }
        }
        return c;
    }

    private static int count5xx(List<Integer> statuses) {
        int c = 0;
        for (Integer s : statuses) {
            if (s >= 500) {
                c++;
            }
        }
        return c;
    }
}
