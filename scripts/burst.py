#!/usr/bin/env python3
"""
On-sale stampede against a running seat-reservation service.

Phases:
  1. setup      - admin creates a fresh show; N buyers register and log in
  2. hot seats  - every buyer fires at the same few "good" seats at once
  3. stampede   - buyers grab random seats; some retry with the same key, some reuse a key
                  with different seats
  4. limit      - one buyer fires 10 parallel single-seat reserves (limit 4)
  5. verify     - prints the outcome distribution and checks the reconciliation invariants

Python standard library only. Usage:
  python3 scripts/burst.py http://localhost:8080
  python3 scripts/burst.py https://my-app.example.com --users 500 --requests 5000 --concurrency 100
"""

import argparse
import http.client
import json
import random
import sys
import threading
import time
import uuid
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urlparse

ADMIN_USER = "admin@seatbooking.local"
ADMIN_PASSWORD = "Admin@12345"
PASSWORD = "burst-pass-123"


class Client:
    """One keep-alive HTTP connection per thread."""

    def __init__(self, base_url, timeout):
        u = urlparse(base_url)
        self.https = u.scheme == "https"
        self.host = u.hostname
        self.port = u.port or (443 if self.https else 80)
        self.timeout = timeout
        self.local = threading.local()

    def _conn(self):
        conn = getattr(self.local, "conn", None)
        if conn is None:
            cls = http.client.HTTPSConnection if self.https else http.client.HTTPConnection
            conn = cls(self.host, self.port, timeout=self.timeout)
            self.local.conn = conn
        return conn

    def call(self, method, path, body=None, token=None, headers=None):
        """Returns (status, parsed_json_or_None). status 0 = network error / timeout."""
        h = {"Content-Type": "application/json"}
        if token:
            h["Authorization"] = "Bearer " + token
        if headers:
            h.update(headers)
        data = json.dumps(body) if body is not None else None
        for attempt in range(2):  # one reconnect if a kept-alive connection was closed
            try:
                conn = self._conn()
                conn.request(method, path, body=data, headers=h)
                resp = conn.getresponse()
                raw = resp.read()
                try:
                    return resp.status, json.loads(raw) if raw else None
                except ValueError:
                    return resp.status, None
            except Exception:
                try:
                    self._conn().close()
                except Exception:
                    pass
                self.local.conn = None
                if attempt == 1:
                    return 0, None
        return 0, None


def run_parallel(n_tasks, concurrency, fn):
    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        return list(pool.map(fn, range(n_tasks)))


def outcome(status, body):
    if status == 201:
        return "201 confirmed"
    if status == 200:
        return "200 idempotent_replay"
    if status == 0:
        return "network_error/timeout"
    code = (body or {}).get("error", "?")
    return "%d %s" % (status, code)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("base_url")
    p.add_argument("--users", type=int, default=1000, help="number of buyers (default 1000)")
    p.add_argument("--seats", type=int, default=500, help="seats in the hall (default 500)")
    p.add_argument("--hot-seats", type=int, default=5, help="number of hot seats stormed (default 5)")
    p.add_argument("--requests", type=int, default=10000, help="stampede requests (default 10000)")
    p.add_argument("--concurrency", type=int, default=200, help="parallel connections (default 200)")
    p.add_argument("--retry-rate", type=float, default=0.10, help="share of stampede requests sent twice with the same key")
    p.add_argument("--timeout", type=float, default=30.0, help="per-request timeout seconds")
    args = p.parse_args()
    if args.seats < args.hot_seats + 12:
        sys.exit("--seats must be at least --hot-seats + 12")

    base = args.base_url.rstrip("/")
    client = Client(base, args.timeout)
    rnd = random.Random()
    run_id = uuid.uuid4().hex[:8]
    t_start = time.time()

    # ------------------------------------------------------------------ 1. setup
    status, ready = client.call("GET", "/health/ready")
    print("readiness: %s %s" % (status, ready))
    if status != 200:
        sys.exit("service is not ready")

    status, body = client.call("POST", "/auth/token", {"user_id": ADMIN_USER, "password": ADMIN_PASSWORD})
    if status != 200:
        sys.exit("admin login failed: %s %s" % (status, body))
    admin = body["token"]

    seats = ["S%d" % i for i in range(1, args.seats + 1)]
    status, show = client.call("POST", "/shows", {"name": "burst-" + run_id, "seats": seats,
                                                  "price_paise": 25000, "per_user_limit": 4}, admin)
    if status != 201:
        sys.exit("create show failed: %s %s" % (status, show))
    show_id = show["id"]
    print("show %s with %d seats" % (show_id, args.seats))

    emails = ["buyer%d-%s@burst.local" % (i, run_id) for i in range(args.users + 1)]

    def signup(i):
        client.call("POST", "/auth/register", {"user_id": emails[i], "password": PASSWORD})
        s, b = client.call("POST", "/auth/token", {"user_id": emails[i], "password": PASSWORD})
        return b["token"] if s == 200 else None

    t0 = time.time()
    tokens = run_parallel(len(emails), min(args.concurrency, 50), signup)
    if any(t is None for t in tokens):
        sys.exit("some buyers failed to register/login")
    limit_buyer_token = tokens.pop()  # reserved for the per-user-limit phase
    print("registered %d buyers in %.1fs" % (len(tokens), time.time() - t0))

    reserve_path = "/shows/%s/reserve" % show_id
    confirmed_seats = defaultdict(list)  # seat -> reservation ids that got 201
    lock = threading.Lock()

    def reserve(token, seat_list, key):
        s, b = client.call("POST", reserve_path, {"seats": seat_list, "idempotency_key": key}, token)
        if s == 201:
            with lock:
                for seat in b["seats"]:
                    confirmed_seats[seat].append(b["reservation_id"])
        return outcome(s, b)

    # ------------------------------------------------------------------ 2. hot-seat storm
    hot = seats[:args.hot_seats]
    hot_targets = [hot[i % len(hot)] for i in range(len(tokens))]
    t0 = time.time()
    hot_results = run_parallel(len(tokens), args.concurrency,
                               lambda i: reserve(tokens[i], [hot_targets[i]], "hot-%d" % i))
    hot_secs = time.time() - t0

    # ------------------------------------------------------------------ 3. stampede
    stampede_seats = seats[args.hot_seats:-10]  # last 10 seats are kept for the limit phase
    plan = []
    for i in range(args.requests):
        buyer = rnd.randrange(len(tokens))
        n = 1 if rnd.random() < 0.7 else 2
        seat_list = rnd.sample(stampede_seats, n)
        key = "st-%d" % i
        plan.append((buyer, seat_list, key))
        if rnd.random() < args.retry_rate:
            plan.append((buyer, seat_list, key))  # same key, same body -> replay, never a 2nd booking
        if rnd.random() < 0.01:
            plan.append((buyer, rnd.sample(stampede_seats, 1), key))  # same key, different body -> 409
    rnd.shuffle(plan)
    t0 = time.time()
    stampede_results = run_parallel(len(plan), args.concurrency,
                                    lambda i: reserve(tokens[plan[i][0]], plan[i][1], plan[i][2]))
    stampede_secs = time.time() - t0

    # ------------------------------------------------------------------ 4. per-user limit
    limit_results = run_parallel(10, 10, lambda i: reserve(limit_buyer_token, [seats[-(i + 1)]], "lim-%d" % i))

    # ------------------------------------------------------------------ 5. verify
    status, final = client.call("GET", "/shows/" + show_id)
    counts = final["counts"]

    def table(title, results, secs=None):
        c = Counter(results)
        rate = " (%.0f req/s)" % (len(results) / secs) if secs else ""
        print("\n%s: %d requests%s" % (title, len(results), rate))
        for k, v in sorted(c.items()):
            print("  %-34s %7d" % (k, v))

    table("HOT-SEAT STORM (%d buyers on %d seats)" % (len(tokens), len(hot)), hot_results, hot_secs)
    table("STAMPEDE", stampede_results, stampede_secs)
    table("PER-USER LIMIT (10 parallel, limit 4)", limit_results)

    all_results = hot_results + stampede_results + limit_results
    five_xx = sum(1 for r in all_results if r[0] == "5")
    net_err = sum(1 for r in all_results if r.startswith("network"))
    double_sold = {s: ids for s, ids in confirmed_seats.items() if len(set(ids)) > 1}
    seats_from_201s = sum(1 for ids in confirmed_seats.values() if ids)
    hot_winners = {s: len(confirmed_seats.get(s, [])) for s in hot}
    limit_ok = sum(1 for r in limit_results if r.startswith("201")) <= 4

    checks = [
        ("no seat confirmed to two reservations", not double_sold),
        ("each hot seat has exactly one winner", all(v == 1 for v in hot_winners.values())),
        ("zero 5xx responses", five_xx == 0),
        ("available + held + confirmed == total_seats",
         counts["available"] + counts["held"] + counts["confirmed"] == final["total_seats"]),
        ("API confirmed count == seats in 201 responses", counts["confirmed"] == seats_from_201s),
        ("per-user limit held (<= 4 of 10)", limit_ok),
    ]

    print("\nFINAL SHOW STATE: total=%d available=%d held=%d confirmed=%d"
          % (final["total_seats"], counts["available"], counts["held"], counts["confirmed"]))
    print("hot-seat winners: %s" % hot_winners)
    print("5xx: %d   network errors/timeouts: %d   total time: %.1fs"
          % (five_xx, net_err, time.time() - t_start))
    print("\nCHECKS")
    for name, ok in checks:
        print("  [%s] %s" % ("PASS" if ok else "FAIL", name))
    sys.exit(0 if all(ok for _, ok in checks) else 1)


if __name__ == "__main__":
    main()
