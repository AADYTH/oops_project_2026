package com.cropscanner.folix.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link FolixSessionEntity}. The delete methods are derived queries and therefore
 * need a surrounding transaction; {@link FolixAuthService} provides one.
 */
public interface FolixSessionRepository extends JpaRepository<FolixSessionEntity, UUID> {

    Optional<FolixSessionEntity> findByTokenHash(String tokenHash);

    long deleteByTokenHash(String tokenHash);

    /** Housekeeping: removes sessions that are already past their expiry. */
    long deleteByExpiresAtBefore(Instant cutoff);

    long deleteByFarmerId(UUID farmerId);
}
