package com.cropscanner.folix.farmeralert;

import java.time.Instant;

/**
 * Privacy-safe representation of an epidemic alert for the receiving farmer.
 *
 * Excludes all sender identity, recipient identity, report/scan IDs, and exact coordinates.
 * Exposes only coarse, privacy-safe details and expiry.
 */
public record FolixFarmerAlertResponse(
        Long alertId,
        String diseaseName,
        String cropName,
        String regionDescription,
        boolean epidemicRisk,
        Instant createdAt,
        Instant expiresAt
) {
    public static FolixFarmerAlertResponse from(FolixFarmerAlertEntity entity) {
        return new FolixFarmerAlertResponse(
                entity.getId(),
                entity.getDiseaseName(),
                entity.getCropName(),
                "Nearby area",
                entity.isEpidemicRisk(),
                entity.getCreatedAt(),
                entity.getExpiresAt()
        );
    }
}
