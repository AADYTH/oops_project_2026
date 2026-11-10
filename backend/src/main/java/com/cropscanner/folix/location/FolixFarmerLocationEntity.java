package com.cropscanner.folix.location;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A farmer's explicit, revocable choice to contribute a coarse region to the Disease Map (Stage 6C).
 *
 * One row per farmer, and a row EXISTS only while the farmer has opted in: no row means "location unavailable"
 * (never asked, denied, or withdrawn). The primary key is the farmer's id, which is always taken from the
 * server-side session ({@code AuthenticatedFarmer#id()}), never from the request.
 *
 * What is stored is the centre of a coarse grid cell ({@link FolixRegionCell}) — NOT the latitude/longitude
 * the browser reported. There is deliberately no exact-coordinate column anywhere in FOLIX.
 *
 * A separate table (rather than columns on {@code folix_farmer}) so the Stage 6A account schema is untouched.
 * The foreign key to {@code folix_farmer} is declared in {@code backend/db/stage6c-location-disease-map.sql}.
 */
@Entity
@Table(name = "folix_farmer_location")
public class FolixFarmerLocationEntity {

    @Id
    @Column(name = "farmer_id")
    private UUID farmerId;

    @Column(name = "region_lat_mdeg", nullable = false)
    private int regionLatMdeg;

    @Column(name = "region_lng_mdeg", nullable = false)
    private int regionLngMdeg;

    /** When the farmer first opted in. */
    @Column(name = "consented_at", nullable = false, updatable = false)
    private Instant consentedAt;

    /** When the region was last (re)set. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected FolixFarmerLocationEntity() {
        // JPA only
    }

    public FolixFarmerLocationEntity(UUID farmerId, FolixRegionCell cell, Instant now) {
        this.farmerId = Objects.requireNonNull(farmerId, "farmerId");
        Objects.requireNonNull(cell, "cell");
        this.regionLatMdeg = cell.latMdeg();
        this.regionLngMdeg = cell.lngMdeg();
        this.consentedAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    /** The farmer chose to refresh their region. Reports saved earlier keep the region they were saved with. */
    public void moveTo(FolixRegionCell cell, Instant now) {
        this.regionLatMdeg = Objects.requireNonNull(cell, "cell").latMdeg();
        this.regionLngMdeg = cell.lngMdeg();
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    public UUID getFarmerId() { return farmerId; }
    public int getRegionLatMdeg() { return regionLatMdeg; }
    public int getRegionLngMdeg() { return regionLngMdeg; }
    public Instant getConsentedAt() { return consentedAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public FolixRegionCell cell() { return new FolixRegionCell(regionLatMdeg, regionLngMdeg); }
}
