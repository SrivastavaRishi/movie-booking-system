# Write-up

Seat Reservation at Scale: design decisions and trade-offs. Deeper detail lives in [CONCURRENCY.md](CONCURRENCY.md), [DB.md](DB.md), [API.md](API.md) and [ASSUMPTIONS.md](ASSUMPTIONS.md).

---

## 1. The atomic decision

**The application never decides who gets a seat; PostgreSQL does, inside one transaction per request.** There is no "read the state, check it in Java, then write" anywhere.

**Mechanism: pessimistic row locks + a conditional update.** For `POST /shows/{id}/reserve`:

```sql
-- lock the requested seats, always in label order
SELECT label, state FROM seats
WHERE show_id = ? AND label = ANY(?) ORDER BY label FOR UPDATE;

-- claim them only if every one is still available
UPDATE seats SET state = 'confirmed', reservation_id = ?
WHERE show_id = ? AND label = ANY(?) AND state = 'available';
```

**Why it is race-free:** `FOR UPDATE` gives one transaction an exclusive lock on each seat row. The check ("is it available?") and the claim (the `UPDATE`) both happen while holding that lock, so nothing can slip in between. When 500 requests hit seat A12, one gets the lock and commits; the other 499 queue on the row lock, then re-read it, see `confirmed`, roll back and get a clean `409 seat_taken`. `seats.reservation_id` records who owns the seat. The row lock acts as a per-seat queue inside PostgreSQL, and requests for different seats never wait on each other.

**One reserve transaction takes these locks, always in this order:**

| Step | Statement | Guarantees |
|------|-----------|------------|
| 1 | `SELECT ... FROM shows WHERE id = ? FOR SHARE` | No booking can slip through while an admin cancels the show (cancel-show takes `FOR UPDATE`) |
| 2 | `INSERT INTO reservations ... ON CONFLICT (user_id, idempotency_key) DO NOTHING` | Exactly-once per idempotency key |
| 3 | `INSERT INTO user_quota ... ON CONFLICT DO UPDATE SET seats_held = seats_held + n WHERE seats_held + n <= limit` | Per-user limit, even with 10 parallel requests from one user |
| 4 | Seat lock + conditional update (above) | No double-sell; all-or-nothing |

Any failed step → `ROLLBACK`, which undoes the earlier steps. That is what makes multi-seat requests **all-or-nothing**.

**Avoiding deadlock for multi-seat requests:** a deadlock needs two transactions each waiting on a lock the other holds. Every transaction (reserve, cancel-reservation, cancel-show) takes locks in the same global order, **show → reservation → user_quota → seats sorted by label**, so a cycle cannot form. Without sorting, `[A1, A2]` and `[A2, A1]` arriving together would deadlock; sorted, both go for A1 first and one simply waits. A test fires 100 such opposite-order requests and asserts one winner and zero errors. As a safety net, a deadlock (`40P01`) would be retried once and then returned as `429`, never `500`.

**Why pessimistic, not optimistic:** the core scenario is maximum contention on the same rows. With optimistic locking (a `version` column), all 500 contenders would run the full transaction, 499 would throw their work away, and non-final conflicts (the per-user quota, multi-seat requests, competitors that later roll back) would need retries, turning into a retry storm exactly when the system is busiest. Pessimistic locks make losers wait briefly and do one cheap re-check. A compare-and-set `WHERE state = 'available'` stays in the `UPDATE` as a guard.

**Defense in depth in the schema:** `PRIMARY KEY (show_id, label)` means a seat exists once per show, and `CHECK ((state = 'available') = (reservation_id IS NULL))` makes "available but owned" impossible.

---

## 2. Idempotency

**Where the key is stored:** in the `reservations` row itself (`idempotency_key` + `request_hash`), protected by `UNIQUE (user_id, idempotency_key)`. The key is scoped **per user**, so two users can never collide on the same key string. It can be sent in the `Idempotency-Key` header or the body (if both are sent, they must match).

**How exactly-once is enforced:** step 2 above inserts the reservation with `ON CONFLICT (user_id, idempotency_key) DO NOTHING`. If two requests with the same key arrive at the same instant, PostgreSQL makes the second insert **wait on the unique index** until the first commits, and then it sees the conflict. No application-level lock, no cache, no race window.

**Replays:** when the key already exists, the stored reservation is loaded:

| Case | Response |
|------|----------|
| Same key, same request (same show + same set of seats, order-insensitive, compared via a SHA-256 `request_hash`) | `200` with the **original** reservation and header `Idempotent-Replayed: true`. Nothing new is booked |
| Same key, different seats or different show | `409 idempotency_key_mismatch` |

`200` rather than `201` is deliberate: a replay is not a new confirmation (the brief lists idempotent replay as a decline reason in metrics), and it keeps "exactly one `201` per seat" literally true.

**Only successes are remembered.** If an attempt is declined (e.g. seat taken), its reservation row is rolled back with the rest of the transaction, so a retry with the same key is a fresh attempt. A key stays used after its reservation is cancelled: replaying it returns the original (now cancelled) reservation.

**PostgreSQL-specific detail:** any error aborts the whole transaction in PostgreSQL, which is why conflicts are handled with `ON CONFLICT` rather than by catching unique-violation exceptions.

---

## 3. Holds & expiry

**Model chosen: reserve = confirmed immediately, plus an explicit owner-only cancel** (`POST /reservations/{id}/cancel`). There is no time-boxed hold, so `held` is reported (to keep the invariant's shape) but is always `0`.

**Why:** the brief's `201` response already says `"status": "confirmed"`, there is no payment step to wait on, and an expiry sweeper is one more moving part that could make counts drift. Explicit cancel keeps every state change inside a request transaction.

**A release can never resurrect someone else's seat:** cancel only touches seats still pointing at *this* reservation:

```sql
UPDATE seats SET state = 'available', reservation_id = NULL
WHERE reservation_id = ?;            -- after locking those seats in label order
```

The reservation row is locked first (`FOR UPDATE`) and checked to be `confirmed`, so two concurrent cancels release the seats and decrement the user's quota **exactly once**; the second sees `cancelled` and returns `200` with no change. Released seats are immediately re-bookable (tested). Only the owner can cancel: the ownership check is in the `WHERE` clause, and another user's reservation is a `404` (not `403`, so its existence isn't revealed).

**Admin show cancel** (`POST /shows/{id}/cancel`) takes the show row `FOR UPDATE`, which waits for in-flight reserves (they hold `FOR SHARE`) and blocks new ones, then marks every reservation `cancelled_by_admin` and returns all seats to `available`. A test races 100 reserves against a cancel and asserts nothing is confirmed on the cancelled show.

**If time-boxed holds were added:** a `held_until` column; reserve sets `state = 'held'`; a confirm endpoint does `UPDATE ... SET state = 'confirmed' WHERE state = 'held' AND reservation_id = ? AND held_until > now()`; a sweeper does `UPDATE ... SET state = 'available', reservation_id = NULL WHERE state = 'held' AND held_until < now()`. Both are conditional updates on current state, so a sweeper can never release a seat that was just confirmed.

---

## 4. Consistency vs availability under a partition

**The service chooses consistency (CP).** There is a single PostgreSQL primary and it is the only source of truth. The app instances are stateless apart from two in-memory helpers that never make decisions.

**If the app cannot reach the database:**

- Reserve and cancel **fail**: lock or pool timeouts become `429` (retryable) and a lost connection becomes `503`. The service never sells a seat it cannot record, and never guesses.
- `/health/ready` runs `SELECT 1` on a separate one-connection pool with a 2 s timeout and returns `503` ("fails closed"), so the platform stops routing traffic. `/health/live` stays `200`, so the platform does not restart the app for a problem a restart cannot fix.
- `GET /shows/{id}` also fails rather than serving stale counts.

**Why not stay available:** for seat sales, a duplicate sale is far worse than a few seconds of "please retry". Accepting bookings during a partition (e.g. on a replica or a local cache) would need reconciliation and apologies afterwards: exactly the double-sell the system exists to prevent.

**The in-memory pieces are safe under any partition:** the "already taken" cache is only used to **reject** (and expires after 2 s), never to confirm, so a stale entry can at worst decline a seat that was freed a moment ago. The concurrency limiter only sheds load.

**At larger scale:** keep a single writer per show (e.g. shard shows across primaries) rather than multi-master writes for the same seats; read replicas could serve `GET /shows/{id}` if slightly stale reads were acceptable, but the reserve path must always hit the primary.

---

## 5. Observability: what I'd get paged for at 2am

**What exists:**

- **Prometheus metrics at `/metrics`:** `reservations_confirmed_total`, `reservations_declined_total{reason}` (`seat_taken`, `per_user_limit`, `idempotent_replay`, `idempotency_key_mismatch`, `rate_limited`, ...), `reservations_cancelled_total{by}`, seat counters, and a `seats{show_id,state}` gauge **read from the database at scrape time**, so it always reconciles with `GET /shows/{id}`. Built-in: HTTP requests by status, HikariCP pool usage, JVM. The burst script checks that counter deltas equal the outcomes it observed.
- **Structured JSON logs:** one line per request (`request_id`, `user_id`, method, path, status, latency) and one per reserve outcome (`outcome`, `show_id`, `seats`, `reservation_id`). `X-Request-Id` is accepted or generated, returned in the response and included in every error body, so a client report can be traced to its log lines.
- **Health:** liveness and a readiness check that verifies the database.

**Page (wake someone up):**

| Alert | Signal | Why it matters |
|-------|--------|----------------|
| Any 5xx | `http_server_requests_seconds_count{status=~"5.."}` rate > 0 for 2 min | The design maps every domain outcome and contention case to 4xx; a 5xx means a bug or a dead dependency |
| Not ready | `/health/ready` failing for > 1 min | Database unreachable: no sales possible |
| Reconciliation broken | `sum(seats)` for a show ≠ its `total_seats`, or a seat referenced by two confirmed reservations (audit query) | Should be impossible by construction; if it fires, correctness is compromised |
| Confirmations stopped during an on-sale | `rate(reservations_confirmed_total[5m]) == 0` while requests are arriving | Users are being turned away for the wrong reason |

**Ticket (look in the morning):** sustained `rate_limited` declines (capacity too small), `hikaricp_connections_pending > 0` for long periods, rising p99 latency, `per_user_limit` spikes (possible scalping/bots), memory or GC pressure, disk on the database volume.

**Not done yet:** alert rules and an alert manager are not configured; the table above is what I would encode.

---

## 6. What I'd do next

1. **Capacity testing on the live instance** with the full ~20k burst, and tuning `RESERVE_MAX_INFLIGHT`, the DB pool and Tomcat from the metrics (HikariCP pending, 429 rate, latency).
2. **Alerting:** encode the paging rules above in Prometheus/Alertmanager.
3. **Public log access:** ship logs to a hosted log service instead of reading them on the instance.
4. **HTTPS** in front of the service (e.g. Caddy with an automatic certificate).
5. **Multiple app instances:** move the "already taken" cache to a shared store (or drop it) and put a load balancer in front; the database logic already supports many instances.
6. **A virtual waiting room** for very large on-sales, admitting buyers at the rate the database can serve.
7. **Holds with payment:** `held` → `confirmed` with a TTL and a sweeper, as sketched in section 3.
8. **Auth hardening:** refresh tokens, revocation, per-user rate limiting against bots.
9. **Product extensions:** multiple venues and seat categories with different prices, partial cancellation, show timings with booking/cancellation cut-offs.
10. **CI:** run the concurrency tests on every push.
