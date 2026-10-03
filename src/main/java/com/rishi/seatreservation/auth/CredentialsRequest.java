package com.rishi.seatreservation.auth;

/** Body of POST /auth/register and POST /auth/token. Any other field (e.g. "role") is ignored. */
public class CredentialsRequest {

    private String userId;
    private String password;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
