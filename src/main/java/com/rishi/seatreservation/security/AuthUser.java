package com.rishi.seatreservation.security;

import java.util.UUID;

/** The caller, as proven by a valid JWT. Never built from the request body. */
public class AuthUser {

    private final UUID userId;
    private final Role role;

    public AuthUser(UUID userId, Role role) {
        this.userId = userId;
        this.role = role;
    }

    public UUID getUserId() {
        return userId;
    }

    public Role getRole() {
        return role;
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
