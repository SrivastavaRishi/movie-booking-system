package com.rishi.seatreservation.auth;

/** Body of POST /auth/register and POST /auth/token. Any other field (e.g. "role") is ignored. */
public class CredentialsRequest {

    private String email;
    private String password;

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
