package com.rishi.seatreservation.common;

public class ErrorResponse {

    private final String error;
    private final String message;
    private final String requestId;

    public ErrorResponse(String error, String message, String requestId) {
        this.error = error;
        this.message = message;
        this.requestId = requestId;
    }

    public String getError() {
        return error;
    }

    public String getMessage() {
        return message;
    }

    public String getRequestId() {
        return requestId;
    }
}
