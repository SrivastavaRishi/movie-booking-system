package com.rishi.seatreservation.common;

import java.util.UUID;

public final class Ids {

    private Ids() {
    }

    /** Parses a UUID path variable; a malformed id is a 400, not a 500. */
    public static UUID parse(String raw, String what) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid(what + " is not a valid id");
        }
    }
}
