# API

All request/response bodies are JSON. Money values are integer paise.

Roles: `USER`, `ADMIN`.

### Error format

Every error response (4xx / 5xx) has the same shape:

```json
{ "error": "seat_taken", "message": "Seat A12 is already taken", "request_id": "…" }
```

- `error` — stable machine-readable code (e.g. `invalid_request`, `unauthorized`, `forbidden`, `seat_taken`).
- `message` — human-readable detail.
- `request_id` — correlation id, also returned in the `X-Request-Id` response header and present in logs.

---

## 1. Register — `POST /auth/register`

Creates a new user and assigns it a UUID `user_id`. Public endpoint (no auth required).
Always creates a `USER` — this endpoint can never create an admin. Admins are pre-seeded (see `ASSUMPTIONS.md`).

### Request

```
POST /auth/register
Content-Type: application/json
```

```json
{ "email": "rishi@example.com", "password": "..." }
```

| Field      | Type   | Required | Rules                                                     |
|------------|--------|----------|-----------------------------------------------------------|
| `email`    | string | yes      | Valid email, max 254 chars. Stored lowercase. Validated on the server even if a frontend validates too. |
| `password` | string | yes      | 8–72 characters                                           |

Any other field in the body (e.g. `"role": "ADMIN"`) is ignored.

### Response — `201 Created`

```json
{
  "user_id": "342c2f0d-1674-4317-a64c-10be5fb7a316",
  "email": "rishi@example.com",
  "role": "USER"
}
```

The password is stored only as a bcrypt hash and is never returned.

### Errors

| Case                                           | Status |
|------------------------------------------------|--------|
| `email` missing / not a valid email            | 400    |
| `password` missing / outside 8–72 characters   | 400    |
| User with this `email` already exists          | 409    |

---

## 2. Get token — `POST /auth/token`

Logs in an existing user and issues a JWT. Public endpoint (no auth required).
Works the same for `USER` and `ADMIN` — the role in the token comes from the database.

### Request

```
POST /auth/token
Content-Type: application/json
```

```json
{ "email": "rishi@example.com", "password": "..." }
```

| Field      | Type   | Required |
|------------|--------|----------|
| `email`    | string | yes      |
| `password` | string | yes      |

### Behaviour

- Look up the user by `email` (lowercased).
- User not found, or password does not match → `401`.
- Otherwise → return a token carrying the user's stored role.

### Response — `200 OK`

```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "token_type": "Bearer",
  "expires_in": 3600,
  "user_id": "342c2f0d-1674-4317-a64c-10be5fb7a316",
  "email": "rishi@example.com",
  "role": "USER"
}
```

Token claims: `sub` (the user's UUID `user_id`), `role` (`USER` / `ADMIN`), `exp` (expiry). Signed with HS256.

### Errors

| Case                                              | Status |
|---------------------------------------------------|--------|
| `email` or `password` missing                     | 400    |
| User not found, or wrong password                 | 401    |

"User not found" and "wrong password" both return the same `401` with the same message, so the API does not reveal which emails are registered.

### Using the token

Send it on authenticated requests:

```
Authorization: Bearer <token>
```

Identity (user id and role) is taken **only** from the token; any `user_id` in a request body is ignored.

---

## 3. Create show — `POST /shows`

Creates a show with its seats. **ADMIN only.**

### Request

```
POST /shows
Authorization: Bearer <admin token>
Content-Type: application/json
```

```json
{
  "name": "friday-night",
  "seats": ["A1", "A2", "A3"],
  "price_paise": 25000,
  "per_user_limit": 4
}
```

| Field            | Type            | Required | Rules                                                                 |
|------------------|-----------------|----------|-----------------------------------------------------------------------|
| `name`           | string          | yes      | 1–200 chars                                                           |
| `seats`          | array of string | yes      | 1–10,000 labels; each 1–16 chars, letters / digits / `-`; no duplicates |
| `price_paise`    | integer         | yes      | > 0 (integer paise, never a float)                                    |
| `per_user_limit` | integer         | no       | ≥ 1, default `4`                                                      |

### Response — `201 Created`

Returns the created show; every seat starts as `available`.

```json
{
  "id": "6f1c2a9e-...",
  "name": "friday-night",
  "status": "active",
  "price_paise": 25000,
  "per_user_limit": 4,
  "total_seats": 3,
  "counts": { "available": 3, "held": 0, "confirmed": 0 },
  "seats": [
    { "label": "A1", "status": "available" },
    { "label": "A2", "status": "available" },
    { "label": "A3", "status": "available" }
  ],
  "created_at": "2026-10-03T10:00:00Z"
}
```

### Errors

| Case                                                        | Status |
|-------------------------------------------------------------|--------|
| Invalid body (missing field, bad seat label, duplicate seats, price ≤ 0, etc.) | 400 |
| Missing / invalid / expired token                           | 401    |
| Token role is not `ADMIN`                                   | 403    |

---

## 4. Get show — `GET /shows/{id}`

Returns the show, the status of every seat, and the counts. **Public — no token required.**

### Request

```
GET /shows/{id}
```

### Response — `200 OK`

```json
{
  "id": "6f1c2a9e-...",
  "name": "friday-night",
  "status": "active",
  "price_paise": 25000,
  "per_user_limit": 4,
  "total_seats": 3,
  "counts": { "available": 1, "held": 0, "confirmed": 2 },
  "seats": [
    { "label": "A1", "status": "confirmed" },
    { "label": "A2", "status": "confirmed" },
    { "label": "A3", "status": "available" }
  ],
  "created_at": "2026-10-03T10:00:00Z"
}
```

- Seats are listed in the order they were given when the show was created.
- Seat `status`: `available` / `held` / `confirmed`.
- Show `status`: `active` / `cancelled`.
- **Invariant:** `counts.available + counts.held + counts.confirmed == total_seats`, always — the counts and seat list are read in a single consistent snapshot.
- Who holds a seat is **not** exposed (no user ids in this public response).

### Errors

| Case                          | Status |
|-------------------------------|--------|
| `id` is not a valid show id   | 400    |
| Show not found                | 404    |

---

## 5. Cancel show — `POST /shows/{id}/cancel`

Cancels the whole show. **ADMIN only.**

### Request

```
POST /shows/{id}/cancel
Authorization: Bearer <admin token>
```

No body.

### Behaviour

- Marks the show `cancelled`.
- Marks every confirmed reservation of the show as `cancelled_by_admin`.
- Returns every seat to `available` (so the invariant still holds) and resets per-user counts for the show.
- After this, any reserve on the show is declined with `409` (reason `show_cancelled`).
- Race-safe with in-flight reserves: a reserve either completes before the cancel (and is then cancelled by it) or is declined — it can never confirm a seat on a cancelled show.
- **Idempotent:** cancelling an already-cancelled show returns `200` with `reservations_cancelled: 0`.
- A cancelled show cannot be restored.
- No refunds are issued (there is no payment step — see `ASSUMPTIONS.md`).

### Response — `200 OK`

```json
{
  "id": "6f1c2a9e-...",
  "status": "cancelled",
  "reservations_cancelled": 12,
  "seats_released": 30
}
```

### Errors

| Case                                | Status |
|-------------------------------------|--------|
| `id` is not a valid show id         | 400    |
| Missing / invalid / expired token   | 401    |
| Token role is not `ADMIN`           | 403    |
| Show not found                      | 404    |

---

## 6. Reserve seats — `POST /shows/{id}/reserve`

Atomically reserves one or more seats for the **token's user**. **USER or ADMIN.**

### Request

```
POST /shows/{id}/reserve
Authorization: Bearer <token>
Idempotency-Key: 7b3e...        (optional here if sent in the body)
Content-Type: application/json
```

```json
{ "seats": ["A12", "A13"], "idempotency_key": "7b3e..." }
```

| Field / header                   | Required | Rules                                                                 |
|----------------------------------|----------|-----------------------------------------------------------------------|
| `seats`                          | yes      | 1 to `per_user_limit` labels; no duplicates; each must exist in the show |
| `idempotency_key` / `Idempotency-Key` | yes (one of them) | 1–128 chars. Accepted in body or header; if both are sent they must match, else `400` |

Any `user_id` in the body is ignored — identity comes only from the token.

### Response — `201 Created` (new reservation)

```json
{
  "reservation_id": "a91f…",
  "show_id": "6f1c2a9e-…",
  "user_id": "342c2f0d-1674-4317-a64c-10be5fb7a316",
  "seats": ["A12", "A13"],
  "amount_paise": 50000,
  "status": "confirmed",
  "created_at": "2026-10-03T10:00:01Z"
}
```

- `amount_paise` = number of seats × show `price_paise`.
- Seats are returned sorted.

### Response — `200 OK` (idempotent replay)

Same key + same request as an earlier **successful** reservation → returns the **original** reservation body unchanged, with header `Idempotent-Replayed: true`. Nothing new is reserved.

`200` (not `201`) is deliberate: a replay is not a new confirmation, so each seat only ever produces one `201`.

### Behaviour

- **All-or-nothing:** if any requested seat is not available, nothing is reserved and the request is declined.
- **No double-sell:** for a seat contended by many users, exactly one gets `201`; all others get `409 seat_taken` — never a `5xx`.
- **Per-user limit:** a user's currently confirmed seats for the show + requested seats must be ≤ `per_user_limit`. Holds under concurrency (parallel requests from one user cannot exceed the limit). Cancelled reservations free up the limit.
- **Idempotency:**
  - The key is scoped **per user** — two users using the same key string never collide.
  - "Same request" = same show + same set of seats (order-insensitive).
  - Same key + different seats, or same key on a different show → `409 idempotency_key_mismatch`.
  - Only **successful** reservations are remembered against a key. If an attempt is declined (e.g. `seat_taken`), nothing is stored and a retry with the same key is treated as a fresh attempt.
  - Two requests with the same key arriving at the same instant still reserve exactly once.
  - A key stays used after its reservation is cancelled: replaying it returns the original reservation (`200`, now with `status: cancelled` / `cancelled_by_admin`). Booking again needs a new key.

### Outcomes

| Case                                                     | Status | `error` code               |
|----------------------------------------------------------|--------|----------------------------|
| New reservation succeeded                                | 201    | —                          |
| Same key + same request (retry)                          | 200    | — (`Idempotent-Replayed: true`) |
| Same key + different request                             | 409    | `idempotency_key_mismatch` |
| Any requested seat already taken                         | 409    | `seat_taken`               |
| Would exceed `per_user_limit`                            | 409    | `per_user_limit`           |
| Show is cancelled                                        | 409    | `show_cancelled`           |
| Missing key, key mismatch between header/body, empty / duplicate seats, too many seats | 400 | `invalid_request` |
| Seat label does not exist in this show                   | 400    | `unknown_seat`             |
| `id` is not a valid show id                              | 400    | `invalid_request`          |
| Missing / invalid / expired token                        | 401    | `unauthorized`             |
| Show not found                                           | 404    | `show_not_found`           |
| Service overloaded (concurrency limit reached)           | 429    | `rate_limited` (+ `Retry-After` header) |

---

## 7. Cancel reservation — `POST /reservations/{id}/cancel`

Cancels a reservation and releases its seats. **USER or ADMIN — owner only** (the user who made the reservation, taken from the token).

### Request

```
POST /reservations/{id}/cancel
Authorization: Bearer <token>
```

No body.

### Response — `200 OK`

```json
{
  "reservation_id": "a91f…",
  "show_id": "6f1c2a9e-…",
  "user_id": "342c2f0d-1674-4317-a64c-10be5fb7a316",
  "seats": ["A12", "A13"],
  "amount_paise": 50000,
  "status": "cancelled",
  "created_at": "2026-10-03T10:00:01Z",
  "cancelled_at": "2026-10-03T10:05:00Z"
}
```

Reservation `status`: `confirmed` / `cancelled` (by owner) / `cancelled_by_admin` (show was cancelled).

### Behaviour

- Cancels the **whole** reservation — partial cancel (some seats only) is not supported.
- Every seat of the reservation goes back to `available`. Seats are released **only if they still belong to this reservation**, so a cancel can never free a seat confirmed to someone else.
- The user's per-user count for the show is reduced by the number of seats, so they can book again.
- **Idempotent:** cancelling an already-cancelled reservation returns `200` with the same body; nothing is released twice. Concurrent cancels of the same reservation release seats exactly once.
- If the reservation was already `cancelled_by_admin` (show cancelled), returns `200` with that status — nothing to do.
- An admin can cancel only **their own** reservations, like any other user.

### Outcomes

| Case                                              | Status | `error` code            |
|---------------------------------------------------|--------|-------------------------|
| Cancelled successfully                            | 200    | —                       |
| Already cancelled (repeat call)                   | 200    | —                       |
| Already `cancelled_by_admin`                      | 200    | —                       |
| Reservation belongs to another user               | 404    | `reservation_not_found` |
| Reservation does not exist                        | 404    | `reservation_not_found` |
| `id` is not a valid reservation id                | 400    | `invalid_request`       |
| Missing / invalid / expired token                 | 401    | `unauthorized`          |

Another user's reservation returns `404` (not `403`) so the API does not reveal that it exists.

---

## 8. Liveness — `GET /health/live`

"Is the app process alive?" Used by the platform to decide whether to **restart** the app. **Public — no token.**

- Checks nothing external (no DB call) — if the app can respond, it is alive.
- A DB outage does **not** make this fail, so the platform does not restart the app for a problem a restart cannot fix.

### Response — `200 OK`

```json
{ "status": "UP" }
```

If the process is hung or dead, there is no response at all (timeout), and the platform restarts it.

---

## 9. Readiness — `GET /health/ready`

"Can the app serve requests right now?" Used by the platform / load balancer to decide whether to **send traffic** to the app. **Public — no token.**

- Checks the database by running `SELECT 1`, with a short timeout (2 seconds) so the check itself never hangs.
- **Fails closed:** if the DB is unreachable or the check times out, it reports `DOWN` — it never reports ready when it isn't.

### Response — `200 OK` (ready)

```json
{ "status": "UP", "checks": { "db": "UP" } }
```

### Response — `503 Service Unavailable` (not ready)

```json
{ "status": "DOWN", "checks": { "db": "DOWN" } }
```

Health endpoints are not subject to the reserve concurrency limit, so they keep answering during a burst.

---

## 10. Metrics — `GET /metrics`

Prometheus text format. **Public — no token** (so it can be scraped; in a real production setup it would be internal only).

### Business metrics

| Metric | Type | Labels | Meaning |
|--------|------|--------|---------|
| `reservations_confirmed_total` | counter | — | Reservations newly confirmed (`201`) |
| `reservation_seats_confirmed_total` | counter | — | Seats confirmed by those reservations |
| `reservations_declined_total` | counter | `reason` | Reserve requests that did not create a reservation. `reason` is the API error code (`seat_taken`, `per_user_limit`, `idempotency_key_mismatch`, `show_cancelled`, `unknown_seat`, `invalid_request`, `show_not_found`, `rate_limited`, ...) or `idempotent_replay` for a `200` replay |
| `reservations_cancelled_total` | counter | `by` = `user` / `admin` | Reservations cancelled by their owner, or by an admin cancelling the show |
| `reservation_seats_released_total` | counter | — | Seats returned to `available` by any cancel |
| `seats` | gauge | `show_id`, `state` = `available` / `held` / `confirmed` | Seats per active show, **read from the database at scrape time** |

### Built-in metrics (Spring Boot / Micrometer)

- `http_server_requests_seconds_count{uri, method, status}` — requests by endpoint and status code (e.g. proves zero `5xx`).
- `hikaricp_connections_active` / `hikaricp_connections_pending` — DB pool usage; pending > 0 means requests are waiting for a connection.
- JVM memory, GC, threads.

### How the metrics reconcile with the API

- The `seats` gauge comes from the same table as `GET /shows/{id}`, so the two always agree (cached for at most 100 ms within one scrape).
- Counters are incremented once per request, after the outcome is final (a confirmation only after commit), so the change in a counter over a burst equals the outcomes clients observed. `./burst.sh` checks this.
- For shows created since the app started: `reservation_seats_confirmed_total − reservation_seats_released_total` = the sum of `seats{state="confirmed"}`.
- Counters live in memory and reset to 0 when the app restarts (normal for Prometheus, which handles resets); the gauge never drifts.

### Example

```
reservations_confirmed_total{application="seat-reservation"} 14.0
reservations_declined_total{application="seat-reservation",reason="seat_taken"} 12.0
reservations_declined_total{application="seat-reservation",reason="per_user_limit"} 8.0
seats{application="seat-reservation",show_id="b964c905-…",state="available"} 15.0
seats{application="seat-reservation",show_id="b964c905-…",state="confirmed"} 15.0
seats{application="seat-reservation",show_id="b964c905-…",state="held"} 0.0
```
