# API

All request/response bodies are JSON. Money values are integer paise.

Roles: `USER`, `ADMIN`.

---

## 1. Register — `POST /auth/register`

Creates a new user. Public endpoint (no auth required).
Always creates a `USER` — this endpoint can never create an admin. Admins are pre-seeded (see `ASSUMPTIONS.md`).

### Request

```
POST /auth/register
Content-Type: application/json
```

```json
{ "user_id": "rishi@example.com", "password": "..." }
```

| Field      | Type   | Required | Rules                                                     |
|------------|--------|----------|-----------------------------------------------------------|
| `user_id`  | string | yes      | Valid email, max 254 chars. Stored lowercase.             |
| `password` | string | yes      | 8–72 characters                                           |

Any other field in the body (e.g. `"role": "ADMIN"`) is ignored.

### Response — `201 Created`

```json
{
  "user_id": "rishi@example.com",
  "role": "USER"
}
```

The password is stored only as a bcrypt hash and is never returned.

### Errors

| Case                                           | Status |
|------------------------------------------------|--------|
| `user_id` missing / not a valid email          | 400    |
| `password` missing / outside 8–72 characters   | 400    |
| User with this `user_id` already exists        | 409    |

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
{ "user_id": "rishi@example.com", "password": "..." }
```

| Field      | Type   | Required |
|------------|--------|----------|
| `user_id`  | string | yes      |
| `password` | string | yes      |

### Behaviour

- Look up the user by `user_id` (lowercased).
- User not found, or password does not match → `401`.
- Otherwise → return a token carrying the user's stored role.

### Response — `200 OK`

```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "token_type": "Bearer",
  "expires_in": 3600,
  "user_id": "rishi@example.com",
  "role": "USER"
}
```

Token claims: `sub` (user id), `role` (`USER` / `ADMIN`), `exp` (expiry). Signed with HS256.

### Errors

| Case                                              | Status |
|---------------------------------------------------|--------|
| `user_id` or `password` missing                   | 400    |
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
