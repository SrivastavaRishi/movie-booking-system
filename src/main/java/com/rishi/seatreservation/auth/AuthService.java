package com.rishi.seatreservation.auth;

import com.rishi.seatreservation.common.ApiException;
import com.rishi.seatreservation.security.JwtService;
import com.rishi.seatreservation.security.Role;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AuthService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int MAX_EMAIL = 254;
    private static final int MIN_PASSWORD = 8;
    private static final int MAX_PASSWORD_BYTES = 72; // bcrypt only uses the first 72 bytes

    private final UserRepository users;
    private final JwtService jwt;
    private final BCryptPasswordEncoder encoder;
    /** Compared against when the user does not exist, so both failure paths take the same time. */
    private final String dummyHash;

    public AuthService(UserRepository users, JwtService jwt, @Value("${app.bcrypt.strength}") int strength) {
        this.users = users;
        this.jwt = jwt;
        this.encoder = new BCryptPasswordEncoder(strength);
        this.dummyHash = encoder.encode("dummy-password-for-timing");
    }

    public UserResponse register(CredentialsRequest req) {
        // Server-side check even if a frontend validates too: the API can be called directly.
        String email = normalizeEmail(req == null ? null : req.getEmail());
        if (!EMAIL.matcher(email).matches() || email.length() > MAX_EMAIL) {
            throw ApiException.invalid("email must be a valid email address (max 254 chars)");
        }
        String password = req.getPassword();
        if (password == null || password.length() < MIN_PASSWORD
                || password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw ApiException.invalid("password must be 8-72 characters");
        }
        UUID id = UUID.randomUUID();
        if (!users.insertUser(id, email, encoder.encode(password))) {
            throw ApiException.conflict("user_exists", "A user with this email already exists");
        }
        return new UserResponse(id, email, Role.USER.name());
    }

    public TokenResponse token(CredentialsRequest req) {
        String email = normalizeEmail(req == null ? null : req.getEmail());
        String password = req == null ? null : req.getPassword();
        if (email.isEmpty() || password == null || password.isEmpty()) {
            throw ApiException.invalid("email and password are required");
        }
        UserRepository.UserRow user = users.findByEmail(email);
        boolean matches = encoder.matches(password, user != null ? user.passwordHash : dummyHash);
        if (user == null || !matches) {
            // Same response for unknown user and wrong password: do not reveal which emails exist.
            throw ApiException.unauthorized("Invalid email or password");
        }
        Role role = Role.valueOf(user.role);
        return new TokenResponse(jwt.issue(user.id, role), jwt.getTtlSeconds(), user.id, user.email, role.name());
    }

    private static String normalizeEmail(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }
}
