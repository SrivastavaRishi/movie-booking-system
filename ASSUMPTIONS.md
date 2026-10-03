# Assumptions

This file lists the assumptions made in this solution.

## Venue & Admin

- **Single venue, single admin.** In a real system there would be many cinema halls / venues, and each venue would have its own admin(s) who manage only that venue's shows. In this solution we model **one venue (studio / movie hall) with one admin**, who is seeded into the `users` table when the database is first set up. Multi-venue support (a `venues` table, admins scoped to a venue) is left as a future extension.
- **Admins are pre-existing.** Admin users are assumed to already be present in the `users` table. There is no API to create admins or promote a user to admin; admins are provisioned directly in the database (seeded via migration).

## Shows

- **Single seat category, single price.** All seats in a show belong to the same category and have the same price (`price_paise` on the show). There are no tiers like Gold / Silver / Recliner with different prices. Seat categories (e.g. a `category` per seat with its own price) are a future extension.

- **No payments, no refunds.** There is no payment step; a reservation is confirmed immediately. When an admin cancels a show, its reservations are marked `cancelled_by_admin` and seats are released — no refund flow is modelled.
- **Cancelled shows are final.** A cancelled show cannot be restored.
- **No show time.** Shows have no start time, so there is no cut-off for booking or cancelling (e.g. "no cancellations within 1 hour of the show"). Show timings are a future extension.

## Reservations

- **Per-user limit — interpretation.** The problem statement says:

  > "**Per-user limit:** a user cannot hold more than `per_user_limit` seats for a show (default 4). Over-limit is a clean decline, not an error."

  and, in the correctness bar:

  > "Per-user limit holds under concurrency (a user firing 10 parallel reserves on a limit=4 show ends with at most 4 held)."

  This could be read as either (a) a cap on seats **per request**, or (b) a cap on the **total** seats a user holds for a show. We enforce **both**:

  | Rule | Example (limit = 4) | Response |
  |------|---------------------|----------|
  | (a) Per request: at most `per_user_limit` seats in a single reserve call | Asking for 5 seats in one call | `400 invalid_request` |
  | (b) Per user per show: total confirmed seats held by the user for that show ≤ `per_user_limit`, across all calls, enforced atomically under concurrency | User holds 3, asks for 2 more | `409 per_user_limit` |

  The limit is **per show** — seats held in one show do not count towards another show. Cancelled reservations free up the limit.

- **Whole-reservation cancel only.** A user cancels an entire reservation; cancelling only some of its seats is not supported (future extension).

## Authentication

- **Users log in with email + password.** Each user gets a UUID `user_id`; the email is a separate unique field. The email format is validated on the server (a frontend may validate too, but the API can be called directly), but the email is not verified — there is no confirmation mail or OTP. Email verification is a future extension.

- **Tokens are bearer tokens.** Auth uses signed (not encrypted) JWTs. Whoever holds a valid token is treated as that user until the token expires, so tokens must only be sent over HTTPS. Tokens are short-lived (1 hour) to limit the damage if one is leaked.
- **No revocation or refresh.** A token cannot be revoked before it expires (no logout / denylist), and there are no refresh tokens — the client simply calls `/auth/token` again. Revocation (e.g. a `token_version` on the user) and refresh tokens are future extensions.
