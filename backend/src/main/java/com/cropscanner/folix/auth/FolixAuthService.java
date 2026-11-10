package com.cropscanner.folix.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Registration, login, logout and "who is this request?" for FOLIX farmers.
 *
 * <h3>Mechanism: server-side sessions in an HttpOnly cookie</h3>
 * <ul>
 *   <li>Login creates a random 256-bit token, stores only its SHA-256 hash in
 *       {@code folix_farmer_session}, and hands the raw token to the browser in an HttpOnly cookie
 *       (see {@link FolixSessionCookies}). JavaScript can never read it.</li>
 *   <li>Every protected request is resolved by {@link FolixAuthInterceptor}, which calls
 *       {@link #authenticate(String)} and puts an {@link AuthenticatedFarmer} on the request. The
 *       identity therefore comes from the server's own session table, never from anything the
 *       browser claims.</li>
 *   <li>Logout deletes the row, so the token stops working immediately (real revocation — which a
 *       stateless JWT cannot do without a denylist).</li>
 * </ul>
 * Full Spring Security was deliberately not introduced: the project has none today, adding the
 * starter would auto-secure every existing endpoint (legacy scan API, H2 console, authority portal)
 * and take over the servlet filter chain, which is well beyond "farmer accounts" and cannot be
 * validated safely without running the app. Only its BCrypt implementation
 * ({@code spring-security-crypto}) is used. Nothing here prevents a later migration: the rest of the
 * code only depends on {@link AuthenticatedFarmer}.
 */
@Service
public class FolixAuthService {

    private static final Logger log = LoggerFactory.getLogger(FolixAuthService.class);

    static final int MIN_PASSWORD_CHARS = 8;
    /** BCrypt only ever uses the first 72 bytes of a password; anything longer is refused rather than silently truncated. */
    static final int MAX_PASSWORD_BYTES = 72;
    static final int MAX_NAME_CHARS = 100;
    static final int MAX_EMAIL_CHARS = 254;

    private static final int TOKEN_BYTES = 32;
    /** 32 random bytes as unpadded Base64url is always exactly 43 characters. */
    private static final int TOKEN_CHARS = 43;
    private static final Pattern TOKEN_SHAPE = Pattern.compile("^[A-Za-z0-9_-]{" + TOKEN_CHARS + "}$");

    private static final Pattern EMAIL_SHAPE = Pattern.compile(
            "^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]{1,64}@"
                    + "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
                    + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$");

    private final FolixFarmerRepository farmers;
    private final FolixSessionRepository sessions;
    private final PasswordEncoder passwordEncoder;
    private final FolixAuthSettings settings;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    /** A real hash of a throw-away value, compared against when the email is unknown so both failure paths cost the same time. */
    private final String timingEqualiserHash;

    @Autowired
    public FolixAuthService(FolixFarmerRepository farmers, FolixSessionRepository sessions,
                            PasswordEncoder passwordEncoder, FolixAuthSettings settings) {
        this(farmers, sessions, passwordEncoder, settings, Clock.systemUTC());
    }

    /** Test seam: lets tests control time (session expiry). */
    FolixAuthService(FolixFarmerRepository farmers, FolixSessionRepository sessions,
                     PasswordEncoder passwordEncoder, FolixAuthSettings settings, Clock clock) {
        this.farmers = farmers;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
        this.settings = settings;
        this.clock = clock;
        this.timingEqualiserHash = passwordEncoder.encode("folix-timing-equaliser-not-a-real-password");
    }

    /** The result of a successful login: who, and the RAW session token to put in the cookie (never stored, never logged). */
    public record LoginResult(AuthenticatedFarmer farmer, String sessionToken, Instant expiresAt) {
        @Override
        public String toString() {
            return "LoginResult[farmer=" + farmer.id() + ", sessionToken=<redacted>]";
        }
    }

    // ------------------------------------------------------------------
    // Registration
    // ------------------------------------------------------------------

    @Transactional
    public FarmerProfileResponse register(RegisterRequest request) {
        if (request == null) {
            throw FolixAuthException.malformed("The registration request body is missing.");
        }

        Map<String, String> problems = new LinkedHashMap<>();
        String name = cleanName(request.name(), problems);
        String email = cleanEmail(request.email(), problems);
        checkNewPassword(request.password(), email, problems);
        if (!problems.isEmpty()) {
            throw FolixAuthException.validation(problems);
        }

        if (farmers.existsByEmail(email)) {
            throw FolixAuthException.emailTaken();
        }

        FolixFarmerEntity entity = new FolixFarmerEntity(
                UUID.randomUUID(), name, email,
                passwordEncoder.encode(request.password()),
                FolixFarmerStatus.ACTIVE, clock.instant());
        try {
            // saveAndFlush so a concurrent duplicate is caught HERE (the unique constraint is the real guard)
            farmers.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            throw FolixAuthException.emailTaken();
        }

        log.info("FOLIX farmer account created: {}", entity.getId());
        return FarmerProfileResponse.from(entity);
    }

    // ------------------------------------------------------------------
    // Login / logout
    // ------------------------------------------------------------------

    @Transactional
    public LoginResult login(LoginRequest request) {
        if (request == null) {
            throw FolixAuthException.malformed("The login request body is missing.");
        }
        Map<String, String> problems = new LinkedHashMap<>();
        if (request.email() == null || request.email().isBlank()) {
            problems.put("email", "Enter your email address.");
        }
        if (request.password() == null || request.password().isEmpty()) {
            problems.put("password", "Enter your password.");
        }
        if (!problems.isEmpty()) {
            throw FolixAuthException.validation(problems);
        }

        String email = normalizeEmail(request.email());
        String password = request.password();
        Optional<FolixFarmerEntity> found = farmers.findByEmail(email);

        // A password longer than BCrypt's limit can never match one that was accepted at registration.
        boolean passwordFits = utf8Length(password) <= MAX_PASSWORD_BYTES;

        boolean authenticated = false;
        if (found.isPresent() && passwordFits) {
            authenticated = passwordEncoder.matches(password, found.get().getPasswordHash());
        } else {
            // Same amount of hashing work whether or not the account exists (limits user enumeration by timing).
            passwordEncoder.matches(passwordFits ? password : "x", timingEqualiserHash);
        }

        if (!authenticated || found.get().getStatus() != FolixFarmerStatus.ACTIVE) {
            log.warn("FOLIX login rejected");
            throw FolixAuthException.invalidCredentials();
        }

        FolixFarmerEntity farmer = found.get();
        Instant now = clock.instant();
        sessions.deleteByExpiresAtBefore(now); // cheap housekeeping so the table does not grow forever

        String token = newToken();
        Instant expiresAt = now.plus(settings.sessionTtl());
        sessions.save(new FolixSessionEntity(UUID.randomUUID(), farmer.getId(), sha256Hex(token), now, expiresAt));

        log.info("FOLIX farmer logged in: {}", farmer.getId());
        return new LoginResult(toPrincipal(farmer), token, expiresAt);
    }

    /** Ends the session for this token. Idempotent: an unknown, expired or malformed token is simply a no-op. */
    @Transactional
    public void logout(String token) {
        if (looksLikeToken(token)) {
            sessions.deleteByTokenHash(sha256Hex(token));
        }
    }

    // ------------------------------------------------------------------
    // Who is this request?
    // ------------------------------------------------------------------

    /**
     * Resolves a raw session token to the farmer it belongs to, or empty when the token is missing,
     * malformed, unknown, revoked, expired, or belongs to an account that is no longer active.
     */
    @Transactional
    public Optional<AuthenticatedFarmer> authenticate(String token) {
        if (!looksLikeToken(token)) {
            return Optional.empty();
        }
        String hash = sha256Hex(token);
        Optional<FolixSessionEntity> found = sessions.findByTokenHash(hash);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        FolixSessionEntity session = found.get();
        if (!session.getExpiresAt().isAfter(clock.instant())) {
            sessions.deleteByTokenHash(hash);
            return Optional.empty();
        }
        return farmers.findById(session.getFarmerId())
                .filter(f -> f.getStatus() == FolixFarmerStatus.ACTIVE)
                .map(FolixAuthService::toPrincipal);
    }

    // ------------------------------------------------------------------
    // Validation helpers
    // ------------------------------------------------------------------

    private static String cleanName(String raw, Map<String, String> problems) {
        if (raw == null || raw.isBlank()) {
            problems.put("name", "Enter your name.");
            return null;
        }
        String name = raw.trim().replaceAll("\\s+", " ");
        if (name.length() > MAX_NAME_CHARS) {
            problems.put("name", "Name is too long (" + MAX_NAME_CHARS + " characters maximum).");
            return null;
        }
        if (name.chars().anyMatch(Character::isISOControl)) {
            problems.put("name", "Name contains characters that are not allowed.");
            return null;
        }
        return name;
    }

    private static String cleanEmail(String raw, Map<String, String> problems) {
        if (raw == null || raw.isBlank()) {
            problems.put("email", "Enter your email address.");
            return null;
        }
        String email = normalizeEmail(raw);
        if (email.length() > MAX_EMAIL_CHARS || !EMAIL_SHAPE.matcher(email).matches()) {
            problems.put("email", "Enter a valid email address.");
            return null;
        }
        String local = email.substring(0, email.indexOf('@'));
        if (local.startsWith(".") || local.endsWith(".") || local.contains("..")) {
            problems.put("email", "Enter a valid email address.");
            return null;
        }
        return email;
    }

    private static void checkNewPassword(String password, String normalizedEmail, Map<String, String> problems) {
        if (password == null || password.isEmpty()) {
            problems.put("password", "Choose a password.");
        } else if (password.isBlank()) {
            problems.put("password", "Password can't be only spaces.");
        } else if (password.length() < MIN_PASSWORD_CHARS) {
            problems.put("password", "Password must be at least " + MIN_PASSWORD_CHARS + " characters.");
        } else if (utf8Length(password) > MAX_PASSWORD_BYTES) {
            problems.put("password", "Password is too long (" + MAX_PASSWORD_BYTES + " characters maximum).");
        } else if (normalizedEmail != null && password.trim().toLowerCase(Locale.ROOT).equals(normalizedEmail)) {
            problems.put("password", "Password must not be the same as your email address.");
        }
    }

    static String normalizeEmail(String raw) {
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private static int utf8Length(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    // ------------------------------------------------------------------
    // Token helpers
    // ------------------------------------------------------------------

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Cheap shape check so garbage cookies never cost a database query. */
    static boolean looksLikeToken(String token) {
        return token != null && TOKEN_SHAPE.matcher(token).matches();
    }

    static String sha256Hex(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static AuthenticatedFarmer toPrincipal(FolixFarmerEntity f) {
        return new AuthenticatedFarmer(f.getId(), f.getName(), f.getEmail(), f.getCreatedAt());
    }
}
