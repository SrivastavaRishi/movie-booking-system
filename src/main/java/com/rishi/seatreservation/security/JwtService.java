package com.rishi.seatreservation.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/** Issues and verifies HS256-signed JWTs carrying the user id (sub) and role. */
@Service
public class JwtService {

    private static final String ROLE_CLAIM = "role";

    private final SecretKey key;
    private final long ttlSeconds;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.ttl-seconds}") long ttlSeconds) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.ttlSeconds = ttlSeconds;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public String issue(String userId, Role role) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(userId)
                .claim(ROLE_CLAIM, role.name())
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttlSeconds * 1000))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** Returns the caller if the token is validly signed and not expired, else null. */
    public AuthUser verify(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String userId = claims.getSubject();
            String role = claims.get(ROLE_CLAIM, String.class);
            if (userId == null || role == null) {
                return null;
            }
            return new AuthUser(userId, Role.valueOf(role));
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }
}
