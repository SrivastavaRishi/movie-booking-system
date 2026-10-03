package com.rishi.seatreservation.auth;

public class TokenResponse {

    private final String token;
    private final String tokenType = "Bearer";
    private final long expiresIn;
    private final String userId;
    private final String role;

    public TokenResponse(String token, long expiresIn, String userId, String role) {
        this.token = token;
        this.expiresIn = expiresIn;
        this.userId = userId;
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

    public String getUserId() {
        return userId;
    }

    public String getRole() {
        return role;
    }
}
