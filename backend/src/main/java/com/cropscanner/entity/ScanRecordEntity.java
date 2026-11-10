package com.cropscanner.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted record of every scan (not just severe ones), added to support:
 *  - the outbreak heat map (Tier 3 upgrade #7) — aggregated by rounded grid
 *    cell, never by exact coordinates, so a single farm's location is never
 *    individually identifiable on the map (see {@code OutbreakService}).
 *  - the treatment-tracking reminder schedule (Tier 3 upgrade #10), via the
 *    linked {@link ReminderEntity} rows.
 *  - the correction/feedback loop (Tier 1 upgrade #2), via the linked
 *    {@link ScanFeedbackEntity} rows.
 *
 * Location is stored ROUNDED to ~1.1km (3 decimal places) precisely so this
 * table can be queried for the public heat map without exposing any single
 * farmer's exact field location. The exact coordinates a severe scan needs
 * for authority dispatch live only in {@link AlertEntity}, which is only
 * ever readable by the authority it was addressed to.
 */
@Entity
@Table(name = "scan_record", indexes = {
        @Index(name = "idx_scan_record_grid", columnList = "roundedLatitude,roundedLongitude"),
        @Index(name = "idx_scan_record_analyzed_at", columnList = "analyzedAt")
})
public class ScanRecordEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private Instant analyzedAt;

    @Column(nullable = false)
    private String cropName;

    @Column(nullable = false)
    private String diseaseName;

    private String plantPart;

    private double confidencePercent;
    private double severityPercent;
    private String confidenceLevel;
    private boolean epidemicRisk;
    private boolean severe;
    private String escalationStatus;

    /** Rounded to ~3 decimal places (~110m) purely for anonymized aggregation; never exact. */
    private Double roundedLatitude;
    private Double roundedLongitude;

    protected ScanRecordEntity() {
    }

    public ScanRecordEntity(UUID id, Instant analyzedAt, String cropName, String diseaseName, String plantPart,
                             double confidencePercent, double severityPercent, String confidenceLevel,
                             boolean epidemicRisk, boolean severe, String escalationStatus,
                             Double roundedLatitude, Double roundedLongitude) {
        this.id = id;
        this.analyzedAt = analyzedAt;
        this.cropName = cropName;
        this.diseaseName = diseaseName;
        this.plantPart = plantPart;
        this.confidencePercent = confidencePercent;
        this.severityPercent = severityPercent;
        this.confidenceLevel = confidenceLevel;
        this.epidemicRisk = epidemicRisk;
        this.severe = severe;
        this.escalationStatus = escalationStatus;
        this.roundedLatitude = roundedLatitude;
        this.roundedLongitude = roundedLongitude;
    }

    public UUID getId() { return id; }
    public Instant getAnalyzedAt() { return analyzedAt; }
    public String getCropName() { return cropName; }
    public String getDiseaseName() { return diseaseName; }
    public String getPlantPart() { return plantPart; }
    public double getConfidencePercent() { return confidencePercent; }
    public double getSeverityPercent() { return severityPercent; }
    public String getConfidenceLevel() { return confidenceLevel; }
    public boolean isEpidemicRisk() { return epidemicRisk; }
    public boolean isSevere() { return severe; }
    public String getEscalationStatus() { return escalationStatus; }
    public Double getRoundedLatitude() { return roundedLatitude; }
    public Double getRoundedLongitude() { return roundedLongitude; }
}
