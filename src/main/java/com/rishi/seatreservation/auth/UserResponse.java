package com.rishi.seatreservation.auth;

public class UserResponse {

    private final String userId;
    private final String role;

    public UserResponse(String userId, String role) {
        this.userId = userId;
        this.role = role;
    }

    public String getUserId() {
        return userId;
    }

    public String getRole() {
        return role;
    }
}
