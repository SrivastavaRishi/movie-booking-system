package com.rishi.seatreservation.auth;

import java.util.UUID;

public class TokenResponse {

    private final String token;
    private final String tokenType = "Bearer";
    private final long expiresIn;
    private final UUID userId;
    private final String email;
    private final String role;

    public TokenResponse(String token, long expiresIn, UUID userId, String email, String role) {
        this.token = token;
        this.expiresIn = expiresIn;
        this.userId = userId;
        this.email = email;
        this.role = role;
    }

    public String getToken() {
        return token;
    }

    public String getTokenType() {
        return tokenType;
    }

    public long getExpiresIn() {
        return expiresIn;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getEmail() {
        return email;
    }

    public String getRole() {
        return role;
    }
}
