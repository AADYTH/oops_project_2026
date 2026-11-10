package com.cropscanner.folix.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * The one place that knows how the session cookie looks.
 *
 * <ul>
 *   <li>{@code HttpOnly} — page JavaScript cannot read it, so an XSS bug cannot steal the session.</li>
 *   <li>{@code Secure} — configurable ({@code folix.auth.cookie-secure}); must be on for HTTPS deployments.</li>
 *   <li>{@code SameSite=Lax} by default — the browser does not attach it to cross-site POSTs.</li>
 *   <li>{@code Path=/api} — it is only sent to the API, never to the static pages.</li>
 * </ul>
 */
@Component
public class FolixSessionCookies {

    static final String COOKIE_PATH = "/api";

    private final FolixAuthSettings settings;

    public FolixSessionCookies(FolixAuthSettings settings) {
        this.settings = settings;
    }

    /** The raw session token the browser sent, if any. Not validated here. */
    public Optional<String> read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie c : cookies) {
            if (settings.cookieName().equals(c.getName()) && c.getValue() != null && !c.getValue().isEmpty()) {
                return Optional.of(c.getValue());
            }
        }
        return Optional.empty();
    }

    public ResponseCookie issue(String token) {
        return base(token).maxAge(settings.sessionTtl()).build();
    }

    /** An immediately-expiring cookie with the same name/path, which makes the browser delete it. */
    public ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(settings.cookieName(), value)
                .httpOnly(true)
                .secure(settings.cookieSecure())
                .sameSite(settings.cookieSameSite())
                .path(COOKIE_PATH);
    }
}
