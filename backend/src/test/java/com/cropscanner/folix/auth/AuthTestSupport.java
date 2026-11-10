package com.cropscanner.folix.auth;

import org.mockito.Mockito;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Shared test helpers for the Stage 6A account tests: an in-memory stand-in for the two repositories
 * (Mockito mocks whose answers read/write plain maps, in the same style the existing FOLIX tests mock
 * their repository) and a clock the test can move forward to expire sessions.
 */
final class AuthTestSupport {

    static final String ORIGIN = "http://localhost:5173";

    private AuthTestSupport() { }

    /** BCrypt at its minimum cost so the suite stays fast; production uses folix.auth.bcrypt-strength (12). */
    static PasswordEncoder encoder() {
        return new BCryptPasswordEncoder(4);
    }

    static FolixAuthSettings settings() {
        return new FolixAuthSettings(Duration.ofHours(168), "FOLIX_SESSION", false, "Lax", Set.of(ORIGIN));
    }

    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-21T10:00:00Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }

        void advance(Duration d) { now = now.plus(d); }
    }

    static final class Store {
        final Map<UUID, FolixFarmerEntity> farmers = new LinkedHashMap<>();
        final Map<String, FolixSessionEntity> sessions = new LinkedHashMap<>();
        final FolixFarmerRepository farmerRepo = Mockito.mock(FolixFarmerRepository.class);
        final FolixSessionRepository sessionRepo = Mockito.mock(FolixSessionRepository.class);

        Store() {
            when(farmerRepo.findByEmail(anyString())).thenAnswer(inv -> byEmail(inv.getArgument(0)));
            when(farmerRepo.existsByEmail(anyString())).thenAnswer(inv -> byEmail(inv.getArgument(0)).isPresent());
            when(farmerRepo.findById(any(UUID.class)))
                    .thenAnswer(inv -> Optional.ofNullable(farmers.get(inv.<UUID>getArgument(0))));
            // FolixReportService takes the report owner as a lazy reference; here it is simply the stored row.
            when(farmerRepo.getReferenceById(any(UUID.class)))
                    .thenAnswer(inv -> farmers.get(inv.<UUID>getArgument(0)));
            when(farmerRepo.saveAndFlush(any(FolixFarmerEntity.class))).thenAnswer(inv -> {
                FolixFarmerEntity e = inv.getArgument(0);
                farmers.put(e.getId(), e);
                return e;
            });

            when(sessionRepo.save(any(FolixSessionEntity.class))).thenAnswer(inv -> {
                FolixSessionEntity e = inv.getArgument(0);
                sessions.put(e.getTokenHash(), e);
                return e;
            });
            when(sessionRepo.findByTokenHash(anyString()))
                    .thenAnswer(inv -> Optional.ofNullable(sessions.get(inv.<String>getArgument(0))));
            when(sessionRepo.deleteByTokenHash(anyString()))
                    .thenAnswer(inv -> sessions.remove(inv.<String>getArgument(0)) == null ? 0L : 1L);
            when(sessionRepo.deleteByExpiresAtBefore(any(Instant.class))).thenAnswer(inv -> {
                Instant cutoff = inv.getArgument(0);
                long before = sessions.size();
                sessions.values().removeIf(s -> s.getExpiresAt().isBefore(cutoff));
                return before - sessions.size();
            });
        }

        private Optional<FolixFarmerEntity> byEmail(String email) {
            return farmers.values().stream().filter(f -> f.getEmail().equals(email)).findFirst();
        }
    }
}
