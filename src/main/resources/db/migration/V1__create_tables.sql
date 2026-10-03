-- Schema described in DB.md.

CREATE TABLE users (
    user_id       VARCHAR(254) PRIMARY KEY,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(16)  NOT NULL CHECK (role IN ('USER', 'ADMIN')),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE shows (
    id             UUID PRIMARY KEY,
    name           VARCHAR(200) NOT NULL,
    price_paise    BIGINT       NOT NULL CHECK (price_paise > 0),
    per_user_limit INT          NOT NULL DEFAULT 4 CHECK (per_user_limit >= 1),
    total_seats    INT          NOT NULL CHECK (total_seats >= 1),
    status         VARCHAR(16)  NOT NULL CHECK (status IN ('active', 'cancelled')),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    cancelled_at   TIMESTAMPTZ
);

CREATE TABLE reservations (
    id              UUID PRIMARY KEY,
    show_id         UUID         NOT NULL REFERENCES shows (id),
    user_id         VARCHAR(254) NOT NULL REFERENCES users (user_id),
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash    CHAR(64)     NOT NULL,
    seats           TEXT[]       NOT NULL,
    amount_paise    BIGINT       NOT NULL CHECK (amount_paise > 0),
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('confirmed', 'cancelled', 'cancelled_by_admin')),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    cancelled_at    TIMESTAMPTZ,
    -- Exactly-once: one reservation per idempotency key per user.
    CONSTRAINT reservations_user_key_uq UNIQUE (user_id, idempotency_key)
);

CREATE INDEX reservations_user_created_idx ON reservations (user_id, created_at);
CREATE INDEX reservations_show_idx ON reservations (show_id);

-- One row per seat per show.
CREATE TABLE seats (
    show_id        UUID        NOT NULL REFERENCES shows (id),
    label          VARCHAR(16) NOT NULL,
    position       INT         NOT NULL,
    state          VARCHAR(16) NOT NULL CHECK (state IN ('available', 'held', 'confirmed')),
    reservation_id UUID REFERENCES reservations (id),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (show_id, label),
    -- A seat is available if and only if no reservation holds it.
    CONSTRAINT seats_state_matches_reservation CHECK ((state = 'available') = (reservation_id IS NULL))
);

CREATE INDEX seats_reservation_idx ON seats (reservation_id);

-- Seats each user currently holds per show; the row locked to enforce the per-user limit.
CREATE TABLE user_quota (
    show_id    UUID         NOT NULL REFERENCES shows (id),
    user_id    VARCHAR(254) NOT NULL REFERENCES users (user_id),
    seats_held INT          NOT NULL CHECK (seats_held >= 0),
    PRIMARY KEY (show_id, user_id)
);
