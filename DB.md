# Database

Database: **PostgreSQL**. Tables are created by Flyway migrations on app startup.

## Why PostgreSQL (over MySQL)

MySQL was the initial choice (familiarity, already installed locally). We switched to PostgreSQL because of how each handles the exact operations this service depends on:

1. **Idempotency in one atomic statement.** `INSERT ... ON CONFLICT (user_id, idempotency_key) DO NOTHING RETURNING id` inserts the reservation or detects the existing key in a single statement. When two requests with the same key race, the second one simply waits for the first to finish. In MySQL (InnoDB), the equivalent `INSERT IGNORE` pattern needs a separate SELECT, and concurrent inserts of the same duplicate key are a known source of **deadlocks**, which would need retry handling to avoid 5xx errors.
2. **Per-user limit in one atomic statement.** `INSERT ... ON CONFLICT DO UPDATE SET seats_held = seats_held + n WHERE seats_held + n <= limit RETURNING seats_held` checks and increments the user's count in one locked step. MySQL's `ON DUPLICATE KEY UPDATE` does not support a `WHERE` condition, so it would need a separate `SELECT ... FOR UPDATE` first — more round trips and more code.
3. **Simpler locking by default.** PostgreSQL's default isolation (READ COMMITTED) locks only the rows a query touches. MySQL's default (REPEATABLE READ) also takes gap locks, which cause extra blocking and deadlocks under concurrent inserts unless the isolation level is changed.
4. **Cleaner types for this schema.** Native `UUID`, `TIMESTAMPTZ` (time-zone safe), `TEXT[]` arrays, and `CHECK` constraints that are always enforced.
5. **Free managed hosting.** The service must run on free tiers. PostgreSQL has several free managed options (e.g. Render, Neon, Supabase); free managed MySQL is rare.

## Tables

| Table          | Purpose                                                                 |
|----------------|-------------------------------------------------------------------------|
| `users`        | Registered users and admins                                             |
| `shows`        | A show (event) with its price and per-user limit                        |
| `seats`        | One row per seat **per show** — the seat inventory and its current state |
| `reservations` | A user's booking of one or more seats, with its idempotency key         |
| `user_quota`   | How many seats each user currently holds per show (enforces the per-user limit) |

## Relationships

```
users 1 ──── * reservations
shows 1 ──── * seats
shows 1 ──── * reservations
reservations 1 ──── * seats        (seats currently held by the reservation)
users 1 ──── * user_quota * ──── 1 shows
```

---

## 1. `users`

| Column          | Type         | Key | Constraints                              | Notes                    |
|-----------------|--------------|-----|------------------------------------------|--------------------------|
| `user_id`       | VARCHAR(254) | PK  |                                          | Email, stored lowercase  |
| `password_hash` | VARCHAR(100) |     | NOT NULL                                 | bcrypt hash              |
| `role`          | VARCHAR(16)  |     | NOT NULL, CHECK in (`USER`, `ADMIN`)     |                          |
| `created_at`    | TIMESTAMPTZ  |     | NOT NULL, default `now()`                |                          |

## 2. `shows`

| Column           | Type         | Key | Constraints                                   | Notes                       |
|------------------|--------------|-----|-----------------------------------------------|-----------------------------|
| `id`             | UUID         | PK  |                                               |                             |
| `name`           | VARCHAR(200) |     | NOT NULL                                      |                             |
| `price_paise`    | BIGINT       |     | NOT NULL, CHECK > 0                           | Integer paise, never float  |
| `per_user_limit` | INT          |     | NOT NULL, default 4, CHECK ≥ 1                |                             |
| `total_seats`    | INT          |     | NOT NULL                                      |                             |
| `status`         | VARCHAR(16)  |     | NOT NULL, CHECK in (`active`, `cancelled`)    |                             |
| `created_at`     | TIMESTAMPTZ  |     | NOT NULL, default `now()`                     |                             |
| `cancelled_at`   | TIMESTAMPTZ  |     | NULL                                          |                             |

## 3. `seats`

One row per seat per show (2 shows × 100 seats = 200 rows).

| Column           | Type        | Key                          | Constraints                                              | Notes                                   |
|------------------|-------------|------------------------------|----------------------------------------------------------|-----------------------------------------|
| `show_id`        | UUID        | PK (part 1), FK → `shows.id` | NOT NULL                                                 |                                         |
| `label`          | VARCHAR(16) | PK (part 2)                  | NOT NULL                                                 | e.g. `A12`                              |
| `position`       | INT         |                              | NOT NULL                                                 | Order the admin listed the seat in (for display) |
| `state`          | VARCHAR(16) |                              | NOT NULL, CHECK in (`available`, `held`, `confirmed`)    |                                         |
| `reservation_id` | UUID        | FK → `reservations.id`       | NULL                                                     | Reservation currently holding the seat  |
| `updated_at`     | TIMESTAMPTZ |                              | NOT NULL, default `now()`                                |                                         |

- Primary key: (`show_id`, `label`).
- CHECK: `(state = 'available') = (reservation_id IS NULL)` — a seat is available if and only if no reservation holds it.
- Index: (`reservation_id`) — to find a reservation's seats on cancel.

## 4. `reservations`

| Column            | Type         | Key                     | Constraints                                                        | Notes                                          |
|-------------------|--------------|-------------------------|--------------------------------------------------------------------|------------------------------------------------|
| `id`              | UUID         | PK                      |                                                                    |                                                |
| `show_id`         | UUID         | FK → `shows.id`         | NOT NULL                                                           |                                                |
| `user_id`         | VARCHAR(254) | FK → `users.user_id`    | NOT NULL                                                           | From the token, never the request body         |
| `idempotency_key` | VARCHAR(128) |                         | NOT NULL                                                           |                                                |
| `request_hash`    | CHAR(64)     |                         | NOT NULL                                                           | SHA-256 of show id + sorted seats              |
| `seats`           | TEXT[]       |                         | NOT NULL                                                           | Seats booked (kept as history after cancel)    |
| `amount_paise`    | BIGINT       |                         | NOT NULL                                                           |                                                |
| `status`          | VARCHAR(20)  |                         | NOT NULL, CHECK in (`confirmed`, `cancelled`, `cancelled_by_admin`) |                                                |
| `created_at`      | TIMESTAMPTZ  |                         | NOT NULL, default `now()`                                          |                                                |
| `cancelled_at`    | TIMESTAMPTZ  |                         | NULL                                                               |                                                |

- UNIQUE: (`user_id`, `idempotency_key`) — guarantees one reservation per key per user.
- Index: (`user_id`, `created_at`) — a user's bookings over time.
- Index: (`show_id`) — all reservations of a show (used when cancelling a show).

## 5. `user_quota`

One row per (show, user) — the row that is locked to enforce the per-user limit atomically.

| Column       | Type         | Key                               | Constraints        | Notes                                      |
|--------------|--------------|-----------------------------------|--------------------|--------------------------------------------|
| `show_id`    | UUID         | PK (part 1), FK → `shows.id`      | NOT NULL           |                                            |
| `user_id`    | VARCHAR(254) | PK (part 2), FK → `users.user_id` | NOT NULL           |                                            |
| `seats_held` | INT          |                                   | NOT NULL, CHECK ≥ 0 | Seats the user currently holds in the show |

- Primary key: (`show_id`, `user_id`).
