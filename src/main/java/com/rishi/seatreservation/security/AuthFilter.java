package com.rishi.seatreservation.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Reads "Authorization: Bearer <jwt>" and, if valid, attaches the caller to the request.
 * It never rejects by itself: each endpoint decides whether it needs a user or an admin (see Auth).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthFilter extends OncePerRequestFilter {

    static final String ATTRIBUTE = AuthUser.class.getName();
    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;

    public AuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(PREFIX)) {
            AuthUser user = jwtService.verify(header.substring(PREFIX.length()).trim());
            if (user != null) {
                request.setAttribute(ATTRIBUTE, user);
                MDC.put("user_id", user.getUserId().toString());
            }
        }
        chain.doFilter(request, response);
    }
}
