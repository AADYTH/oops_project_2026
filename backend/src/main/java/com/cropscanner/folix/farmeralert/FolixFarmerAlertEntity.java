package com.cropscanner.folix.farmeralert;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A nearby farmer epidemic alert (FOLIX Stage 6I).
 *
 * Persisted per recipient farmer when an authenticated farmer explicitly triggers alerts
 * from an owned report diagnosed with an epidemic-risk disease.
 *
 * Privacy guarantees:
 * - Does NOT store or expose the triggering farmer's identity or account ID.
 * - Does NOT store or expose scan or report IDs.
 * - Stores only coarse region cell coordinates (never exact GPS).
 *
 * Lifecyle:
 * - Pre-computed {@code expiresAt} (7 days after creation).
 * - Enforces a 24-hour cooldown per recipient farmer and disease name before creation.
 */
@Entity
@Table(name = "folix_farmer_alert")
public class FolixFarmerAlertEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recipient_farmer_id", nullable = false)
    private UUID recipientFarmerId;

    @Column(name = "disease_name", nullable = false)
    private String diseaseName;

    @Column(name = "crop_name", nullable = false)
    private String cropName;

    @Column(name = "region_lat_mdeg", nullable = false)
    private int regionLatMdeg;

    @Column(name = "region_lng_mdeg", nullable = false)
    private int regionLngMdeg;

    @Column(name = "epidemic_risk", nullable = false)
    private boolean epidemicRisk;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected FolixFarmerAlertEntity() {
        // JPA only
    }

    public FolixFarmerAlertEntity(UUID recipientFarmerId, String diseaseName, String cropName,
                                  int regionLatMdeg, int regionLngMdeg, boolean epidemicRisk,
                                  Instant createdAt, Instant expiresAt) {
        this.recipientFarmerId = Objects.requireNonNull(recipientFarmerId, "recipientFarmerId");
        this.diseaseName = Objects.requireNonNull(diseaseName, "diseaseName");
        this.cropName = Objects.requireNonNull(cropName, "cropName");
        this.regionLatMdeg = regionLatMdeg;
        this.regionLngMdeg = regionLngMdeg;
        this.epidemicRisk = epidemicRisk;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public Long getId() { return id; }
    public UUID getRecipientFarmerId() { return recipientFarmerId; }
    public String getDiseaseName() { return diseaseName; }
    public String getCropName() { return cropName; }
    public int getRegionLatMdeg() { return regionLatMdeg; }
    public int getRegionLngMdeg() { return regionLngMdeg; }
    public boolean isEpidemicRisk() { return epidemicRisk; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
