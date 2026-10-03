package com.rishi.seatreservation.security;

import com.rishi.seatreservation.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;

/** Access checks used by controllers. Identity always comes from the verified token. */
public final class Auth {

    private Auth() {
    }

    /** Any logged-in user (USER or ADMIN); 401 otherwise. */
    public static AuthUser requireUser(HttpServletRequest request) {
        Object user = request.getAttribute(AuthFilter.ATTRIBUTE);
        if (!(user instanceof AuthUser)) {
            throw ApiException.unauthorized("Missing, invalid or expired token");
        }
        return (AuthUser) user;
    }

    /** ADMIN only; 401 without a valid token, 403 for a non-admin. */
    public static AuthUser requireAdmin(HttpServletRequest request) {
        AuthUser user = requireUser(request);
        if (!user.isAdmin()) {
            throw ApiException.forbidden("Admin role required");
        }
        return user;
    }
}
