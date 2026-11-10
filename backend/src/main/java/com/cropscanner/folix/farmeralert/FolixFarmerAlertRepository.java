package com.cropscanner.folix.farmeralert;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Persistence for {@link FolixFarmerAlertEntity}.
 */
public interface FolixFarmerAlertRepository extends JpaRepository<FolixFarmerAlertEntity, Long> {

    /**
     * Active alerts for the given recipient farmer (where {@code expiresAt > now}), sorted newest first.
     */
    List<FolixFarmerAlertEntity> findByRecipientFarmerIdAndExpiresAtAfterOrderByCreatedAtDesc(
            UUID recipientFarmerId, Instant now);

    /**
     * Checks if the recipient farmer already received an alert for the specified disease within the cooldown window
     * (i.e. created after {@code cooldownCutoff}).
     */
    boolean existsByRecipientFarmerIdAndDiseaseNameAndCreatedAtAfter(
            UUID recipientFarmerId, String diseaseName, Instant cooldownCutoff);

    long deleteByRecipientFarmerId(UUID recipientFarmerId);
}
