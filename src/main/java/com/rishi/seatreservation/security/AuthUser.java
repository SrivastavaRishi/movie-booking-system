package com.rishi.seatreservation.security;

/** The caller, as proven by a valid JWT. Never built from the request body. */
public class AuthUser {

    private final String userId;
    private final Role role;

    public AuthUser(String userId, Role role) {
        this.userId = userId;
        this.role = role;
    }

    public String getUserId() {
        return userId;
    }

    public Role getRole() {
        return role;
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
