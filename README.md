# Seat Reservation Service

A JSON HTTP API that sells assigned seats for a show and stays correct under heavy concurrency: a seat is never sold twice, a user never exceeds the per-show limit, and a retried request never books twice.

**Stack:** Java 21 · Spring Boot 3 · PostgreSQL 16 · Flyway · JdbcTemplate · Docker

| Doc | What's in it |
|-----|--------------|
| [API.md](API.md) | Every endpoint: request, response, errors |
| [DB.md](DB.md) | Tables, columns, keys, and why PostgreSQL |
| [CONCURRENCY.md](CONCURRENCY.md) | How double-sells, limits and retries are prevented |
| [ASSUMPTIONS.md](ASSUMPTIONS.md) | Scope decisions and interpretations of the brief |

---

## Quick start (Docker — recommended)

The only requirement is **Docker** with the Compose plugin (Docker Desktop on Mac/Windows, Docker Engine on Linux). Java, Maven and PostgreSQL are **not** needed on the machine — they run inside containers.

```bash
git clone https://github.com/SrivastavaRishi/movie-booking-system.git
cd movie-booking-system
docker compose up --build -d
```

This builds the app image, starts PostgreSQL, waits until the database is healthy, then starts the app. On startup the app creates the tables and seeds the admin user (Flyway migrations).

Check it is up:

```bash
curl http://localhost:8080/health/ready
# {"status":"UP","checks":{"db":"UP"}}
```

| Service    | Address on your machine                      |
|------------|----------------------------------------------|
| API        | `http://localhost:8080`                      |
| PostgreSQL | `localhost:5433` (db / user / password: `seatbooking`) |

Useful commands:

```bash
docker compose ps              # status of the containers
docker compose logs -f app     # follow the app's JSON logs
docker compose down            # stop (database data is kept)
docker compose down -v         # stop and delete the database data
```

---

## Running without Docker

Requirements: **JDK 21**, **Maven 3.9+**, **PostgreSQL 16** running on `localhost:5432`.

```bash
# one-time: create the database and user
psql -d postgres -c "CREATE ROLE seatbooking LOGIN PASSWORD 'seatbooking';"
psql -d postgres -c "CREATE DATABASE seatbooking OWNER seatbooking;"

# build and run
mvn -DskipTests package
java -jar target/app.jar
```

---

## Try it

Admin login (seeded by migration `V2`): **`admin@seatbooking.local` / `Admin@12345`**

```bash
B=http://localhost:8080
J='Content-Type: application/json'

# admin token
ADMIN=$(curl -s -XPOST $B/auth/token -H "$J" \
  -d '{"email":"admin@seatbooking.local","password":"Admin@12345"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')

# create a show (admin only)
SHOW=$(curl -s -XPOST $B/shows -H "$J" -H "Authorization: Bearer $ADMIN" \
  -d '{"name":"friday-night","seats":["A1","A2","A3"],"price_paise":25000}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')

# register a user and get a token
curl -s -XPOST $B/auth/register -H "$J" -d '{"email":"alice@example.com","password":"password1"}'
USER=$(curl -s -XPOST $B/auth/token -H "$J" \
  -d '{"email":"alice@example.com","password":"password1"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')

# reserve a seat (201), retry with the same key (200, same reservation)
curl -s -XPOST $B/shows/$SHOW/reserve -H "$J" -H "Authorization: Bearer $USER" \
  -d '{"seats":["A1"],"idempotency_key":"order-1"}'

# show state (public)
curl -s $B/shows/$SHOW
```

**Postman:** import [`postman/seat-reservation.postman_collection.json`](postman/seat-reservation.postman_collection.json) and run the collection top to bottom. Tokens and ids are saved into collection variables automatically; change `baseUrl` to target another environment.

---

## Burst test (one command)

Reproduces the on-sale stampede against any running instance and prints the outcome distribution and reconciliation checks. Needs only **Python 3** (standard library).

```bash
./burst.sh http://localhost:8080
./burst.sh https://<your-deployed-url> --users 500 --requests 5000 --concurrency 100
```

What it does:

1. Creates a fresh show and registers N buyers.
2. **Hot-seat storm:** every buyer fires at the same few seats at once.
3. **Stampede:** buyers grab random seats; some retry with the same idempotency key, some reuse a key with different seats.
4. **Per-user limit:** one buyer fires 10 parallel reserves on a limit-4 show.
5. Prints the distribution (`201` / `200` replay / `409` by reason / `5xx`) and checks:
   no seat confirmed twice · exactly one winner per hot seat · zero 5xx · `available + held + confirmed == total_seats` · API counts match the 201s · per-user limit held.

Exit code is `0` only if every check passes. Options: `--users`, `--seats`, `--hot-seats`, `--requests`, `--concurrency`, `--retry-rate`, `--timeout` (see `./burst.sh <url> --help`).

On Windows, run `python scripts/burst.py <BASE_URL>` instead of `./burst.sh`.

---

## Tests

Concurrency tests fire real parallel HTTP requests at the app backed by a **real PostgreSQL** (no mocks): hot-seat race, per-user limit under parallel requests, same-key retries, opposite-order multi-seat requests (deadlock check), reserves racing a show cancel, concurrent cancels, and token-vs-body identity.

They expect PostgreSQL on `localhost:5432` with a `seatbooking_test` database:

```bash
psql -d postgres -c "CREATE DATABASE seatbooking_test OWNER seatbooking;"
mvn test
```

(Override with `TEST_DB_URL`, `TEST_DB_USER`, `TEST_DB_PASSWORD`.)

---

## Configuration

All settings come from environment variables, so the same image runs locally, in Compose and on any cloud platform.

| Variable | Default | Purpose |
|----------|---------|---------|
| `PORT` | `8080` | HTTP port |
| `DB_URL` | `jdbc:postgresql://localhost:5432/seatbooking` | JDBC URL |
| `DB_USER` / `DB_PASSWORD` | `seatbooking` / `seatbooking` | DB credentials |
| `DB_POOL_SIZE` | `20` | Max DB connections |
| `DB_POOL_TIMEOUT_MS` | `3000` | Wait for a free connection before failing (→ 429) |
| `DB_LOCK_TIMEOUT` / `DB_STATEMENT_TIMEOUT` | `5s` / `10s` | PostgreSQL lock / statement timeouts (→ 429) |
| `JWT_SECRET` | dev value | HS256 signing key, **≥ 32 bytes — always set in production** |
| `JWT_TTL_SECONDS` | `3600` | Token lifetime |
| `BCRYPT_STRENGTH` | `10` | Password hashing cost |
| `RESERVE_MAX_INFLIGHT` | `20` | Reserve/cancel requests working in the DB at once |
| `RESERVE_QUEUE_TIMEOUT_MS` | `2000` | Wait for a slot before returning 429 |
| `TAKEN_CACHE_TTL_MS` | `2000` | In-memory "seat taken" cache TTL (`0` disables) |
| `TOMCAT_MAX_THREADS` / `TOMCAT_ACCEPT_COUNT` / `TOMCAT_MAX_CONNECTIONS` | `200` / `1000` / `10000` | HTTP server capacity |
| `LOG_FORMAT` | `logstash` | JSON log lines with `request_id` and `user_id` |

---

## Endpoints at a glance

| Method | Path | Access |
|--------|------|--------|
| POST | `/auth/register` | Public |
| POST | `/auth/token` | Public |
| POST | `/shows` | ADMIN |
| GET | `/shows/{id}` | Public |
| POST | `/shows/{id}/cancel` | ADMIN |
| POST | `/shows/{id}/reserve` | USER / ADMIN |
| POST | `/reservations/{id}/cancel` | Owner only |
| GET | `/health/live` | Public — liveness |
| GET | `/health/ready` | Public — readiness (checks the DB, `503` if down) |

Full details in [API.md](API.md).
