package com.cropscanner.folix.agrinetwork;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A farmer's optional agricultural/farm information (Stage 6D) — the Agricultural Network's profile
 * foundation. Deliberately minimal, same pattern as {@code FolixFarmerLocationEntity} (Stage 6C): a
 * separate table keyed by the farmer's id, not new columns on {@code folix_farmer}, so the Stage 6A
 * account schema is untouched.
 *
 * A row EXISTS only once a farmer has saved something here; no row simply means "not filled in yet" —
 * this information is never required for basic account access. There is intentionally no farm location
 * (that stays in Stage 6C's coarse-region system), no government ID, no bank detail and nothing beyond a
 * free-text village/area name, the crop(s) grown and a preferred support category.
 */
@Entity
@Table(name = "folix_farmer_agri_profile")
public class FolixFarmerAgriProfileEntity {

    @Id
    @Column(name = "farmer_id")
    private UUID farmerId;

    @Column(name = "village_or_area", length = 150)
    private String villageOrArea;

    /** Free text, e.g. "Rice, banana". Not a controlled vocabulary — kept simple for this prototype. */
    @Column(name = "crops", length = 300)
    private String crops;

    @Enumerated(EnumType.STRING)
    @Column(name = "preferred_category", length = 40)
    private FolixSupportCategory preferredCategory;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected FolixFarmerAgriProfileEntity() {
        // JPA only
    }

    public FolixFarmerAgriProfileEntity(UUID farmerId, String villageOrArea, String crops,
                                         FolixSupportCategory preferredCategory, Instant now) {
        this.farmerId = Objects.requireNonNull(farmerId, "farmerId");
        this.villageOrArea = villageOrArea;
        this.crops = crops;
        this.preferredCategory = preferredCategory;
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    public void apply(String villageOrArea, String crops, FolixSupportCategory preferredCategory, Instant now) {
        this.villageOrArea = villageOrArea;
        this.crops = crops;
        this.preferredCategory = preferredCategory;
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    public UUID getFarmerId() { return farmerId; }
    public String getVillageOrArea() { return villageOrArea; }
    public String getCrops() { return crops; }
    public FolixSupportCategory getPreferredCategory() { return preferredCategory; }
    public Instant getUpdatedAt() { return updatedAt; }
}
