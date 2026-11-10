package com.cropscanner.folix.auth;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * Immutable FOLIX auth configuration (see the {@code folix.auth.*} block in application.yml).
 *
 * @param sessionTtl     absolute lifetime of a login session (it does not slide)
 * @param cookieName     name of the HttpOnly session cookie
 * @param cookieSecure   the cookie's {@code Secure} flag — true whenever the site is served over HTTPS
 * @param cookieSameSite {@code Lax} (default), {@code Strict} or {@code None} (cross-site deployments; requires Secure)
 * @param allowedOrigins browser origins allowed to make state-changing calls (the CSRF Origin check);
 *                       taken from the same property as CORS, {@code cropscanner.cors.allowed-origin}
 */
public record FolixAuthSettings(Duration sessionTtl, String cookieName, boolean cookieSecure,
                                String cookieSameSite, Set<String> allowedOrigins) {

    public FolixAuthSettings {
        if (sessionTtl == null || sessionTtl.isZero() || sessionTtl.isNegative()) {
            throw new IllegalArgumentException("folix.auth.session-ttl-hours must be positive");
        }
        if (cookieName == null || cookieName.isBlank()) {
            throw new IllegalArgumentException("folix.auth.cookie-name must not be blank");
        }
        String sameSite = cookieSameSite == null ? "" : cookieSameSite.trim();
        String canonical = switch (sameSite.toLowerCase(Locale.ROOT)) {
            case "lax" -> "Lax";
            case "strict" -> "Strict";
            case "none" -> "None";
            default -> throw new IllegalArgumentException("folix.auth.cookie-same-site must be Lax, Strict or None");
        };
        if ("None".equals(canonical) && !cookieSecure) {
            // Browsers silently drop SameSite=None cookies that are not Secure: fail loudly instead.
            throw new IllegalArgumentException("folix.auth.cookie-same-site=None requires folix.auth.cookie-secure=true");
        }
        cookieSameSite = canonical;
        allowedOrigins = allowedOrigins == null ? Set.of() : Set.copyOf(allowedOrigins);
    }

    /** Canonical form used for origin comparison: trimmed, lower-case, no trailing slash. */
    public static String normalizeOrigin(String origin) {
        if (origin == null) return "";
        String o = origin.trim().toLowerCase(Locale.ROOT);
        while (o.endsWith("/")) {
            o = o.substring(0, o.length() - 1);
        }
        return o;
    }
}
