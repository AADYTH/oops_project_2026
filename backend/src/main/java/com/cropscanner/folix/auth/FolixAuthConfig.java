package com.cropscanner.folix.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Wires the account system's password hashing and settings.
 *
 * Only {@code spring-security-crypto} is on the classpath (for BCrypt) — NOT the full Spring
 * Security starter — so no default security filter chain is installed and every existing endpoint
 * behaves exactly as before. See {@link FolixAuthService} for why.
 */
@Configuration
public class FolixAuthConfig {

    @Bean
    public FolixAuthSettings folixAuthSettings(
            @Value("${folix.auth.session-ttl-hours:168}") long sessionTtlHours,
            @Value("${folix.auth.cookie-name:FOLIX_SESSION}") String cookieName,
            @Value("${folix.auth.cookie-secure:false}") boolean cookieSecure,
            @Value("${folix.auth.cookie-same-site:Lax}") String cookieSameSite,
            @Value("${cropscanner.cors.allowed-origin:http://localhost:5173}") String allowedOriginsCsv) {
        Set<String> origins = Arrays.stream(allowedOriginsCsv.split(","))
                .map(FolixAuthSettings::normalizeOrigin)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        return new FolixAuthSettings(Duration.ofHours(sessionTtlHours), cookieName, cookieSecure,
                cookieSameSite, origins);
    }

    /** BCrypt with a per-password random salt. Strength 12 by default (roughly a quarter of a second per hash). */
    @Bean
    public PasswordEncoder folixPasswordEncoder(@Value("${folix.auth.bcrypt-strength:12}") int strength) {
        return new BCryptPasswordEncoder(strength);
    }
}
