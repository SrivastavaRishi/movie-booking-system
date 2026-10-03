# Concurrency Design

How the service stays correct when thousands of requests hit the same show at once.

## 1. Layers

```
HTTP request
  → Filters: request-id + JWT auth (who is calling)
  → Concurrency limiter (Semaphore → 429 when overloaded)
  → Controller (validate input, map to/from JSON)
  → Service (business rules; ONE @Transactional method per use case)
  → Repository (plain SQL via JdbcTemplate)
  → PostgreSQL (where the concurrency decisions are made)
```

**Data access: JdbcTemplate (plain SQL), not JPA/Hibernate.** Correctness depends on exact SQL — `ON CONFLICT`, `FOR UPDATE`, `ORDER BY`, conditional `UPDATE`s. JPA hides the SQL and defaults to read-then-write, which is exactly what double-sells under load. With plain SQL, every lock is visible in the code.

## 2. Core principle

**The application never decides; the database does.** There is no "read state → check in Java → write". Every decision is one of:

- a **conditional write** — `UPDATE ... WHERE state = 'available'`, then check the affected row count;
- a **unique constraint** — the idempotency key;
- a **row lock** — `FOR UPDATE` / `FOR SHARE`, which makes concurrent requests take turns.

Isolation level: **READ COMMITTED** (PostgreSQL default).

## 3. Reserve — one transaction

```
BEGIN
 ① Lock show row (FOR SHARE)            → show active?      no → 409 show_cancelled
 ② Insert reservation (ON CONFLICT)     → key new?          no → 200 replay / 409 idempotency_key_mismatch
 ③ Conditional upsert on user_quota     → within limit?     no → 409 per_user_limit
 ④ Lock seats (FOR UPDATE, sorted)
    + UPDATE ... WHERE state='available' → all seats free?   no → 409 seat_taken
COMMIT → 201
```

Any "no" → **ROLLBACK**: everything done earlier in the transaction is undone. That is what makes the request all-or-nothing.

| Step | Race it prevents | How |
|------|------------------|-----|
| ① `SELECT ... FROM shows WHERE id = ? FOR SHARE` | A reserve slipping through while the admin cancels the show | Many reserves can hold a *shared* lock at the same time. Show-cancel needs an *exclusive* lock, so it waits for in-flight reserves to finish; new reserves wait for the cancel, then see `cancelled`. |
| ② `INSERT INTO reservations ... ON CONFLICT (user_id, idempotency_key) DO NOTHING RETURNING id` | The same key retried at the same instant | The unique index makes the 2nd insert wait until the 1st commits, then it sees the conflict → exactly once. |
| ③ `INSERT INTO user_quota ... ON CONFLICT (show_id, user_id) DO UPDATE SET seats_held = user_quota.seats_held + :n WHERE user_quota.seats_held + :n <= :limit RETURNING seats_held` | 10 parallel requests from one user | Row lock on (show, user): the requests go one at a time, each re-checking the limit. No row returned → over limit. |
| ④ `SELECT ... FROM seats WHERE show_id = ? AND label = ANY(?) ORDER BY label FOR UPDATE`, then `UPDATE seats SET state = 'confirmed', reservation_id = ? WHERE show_id = ? AND label = ANY(?) AND state = 'available'` | 500 users on seat A12 | The first gets the lock and confirms. The rest wait, then re-check `state = 'available'` → false → 409. Updated rows ≠ requested seats → seat taken. |

Notes:

- If an attempt is declined, the reservation row from step ② is rolled back too, so a retry with the same key is a fresh attempt.
- PostgreSQL aborts the whole transaction on any error, which is why steps ② and ③ use `ON CONFLICT` instead of catching unique-violation errors.

## 4. No deadlocks — global lock order

A deadlock needs two transactions each waiting for a lock the other holds. It cannot happen when **every transaction takes locks in the same order**:

```
show → reservation → user_quota → seats (sorted by label)
```

- Reserve, cancel-reservation and cancel-show all follow this order.
- Sorting seats matters: without it, request X locks A1 then wants A2 while request Y locks A2 then wants A1 → deadlock. Sorted, both go for A1 first, so one simply waits.

## 5. Cancel reservation — one transaction

```
BEGIN
 ① Lock show row (FOR SHARE)
 ② Lock reservation row: SELECT ... WHERE id = ? AND user_id = <token user> FOR UPDATE
      not found → 404;  already cancelled → 200 (nothing to do)
 ③ user_quota: seats_held = seats_held - n
 ④ UPDATE seats SET state = 'available', reservation_id = NULL WHERE reservation_id = <this reservation>
 ⑤ Mark reservation cancelled
COMMIT
```

- **Two cancels at once:** the row lock in ② makes the second wait; it then sees `cancelled` and releases nothing.
- **Never releases someone else's seat:** ④ only matches seats still pointing to *this* reservation.

## 6. Cancel show (admin) — one transaction

```
BEGIN
 ① Lock show row (FOR UPDATE)   ← waits for in-flight reserves; blocks new ones
 ② Mark show cancelled
 ③ Mark all its confirmed reservations cancelled_by_admin
 ④ All seats of the show → available;  user_quota rows of the show → 0
COMMIT
```

Reserves that were waiting on ① then see `cancelled` → `409 show_cancelled`.

## 7. Protection in front of the DB (load, not correctness)

These make the service faster under a burst. **Correctness never depends on them.**

1. **Concurrency limiter (Semaphore).** At most `N` reserve requests run inside the DB at once (`N` ≈ connection pool size, configurable via env var). Others wait briefly, then get `429` with `Retry-After`.
2. **"Already taken" in-memory set.** When the DB confirms a seat, or declines a request because a seat is taken, the seat is remembered in memory for a short TTL (`TAKEN_CACHE_TTL_MS`, default 2s). Later requests for it get `409 seat_taken` without touching the DB. Entries are removed on cancel.
   - Used **only to reject**, never to confirm — the DB alone decides who gets a seat, so a stale entry can never cause a double-sell. Worst case: a just-released seat is declined for up to the TTL.
   - Only the database refreshes an entry; a decline from memory never extends it, so a stale entry always expires.
   - A retry of the user's **own** successful request must still be replayed (`200`), so on a cache hit we first look up the user's idempotency key (a cheap indexed read, no locks) and only decline if it is unused.
   - Valid for a **single instance**. Multiple instances would need a shared store.

## 8. Never a 5xx

- Domain outcomes (seat taken, limit, mismatch, cancelled show) → 4xx via a global exception handler.
- PostgreSQL `lock_timeout` and `statement_timeout` are set so nothing hangs forever; a timeout → `429` (retryable).
- Connection-pool timeout (no free DB connection) → `429`.
- Deadlock (`40P01`) should not happen given the lock order; as a safety net it is retried once, then `429`.

## 9. How it is tested

Concurrency tests run against a **real PostgreSQL** (not mocks), using `ExecutorService` + `CountDownLatch` so all threads fire at the same instant:

| Test | Expected |
|------|----------|
| 500 threads reserve the same seat | exactly 1 × `201`, 499 × `409 seat_taken` |
| 1 user, 10 parallel reserves, limit 4 | at most 4 seats held |
| 50 parallel retries with the same key | exactly 1 reservation |
| Same key, different seats | `409 idempotency_key_mismatch` |
| Reserves racing a show cancel | no confirmed seat on a cancelled show |
| Concurrent cancels of one reservation | seats released once, quota decremented once |
| After every test | `available + held + confirmed == total_seats` |
