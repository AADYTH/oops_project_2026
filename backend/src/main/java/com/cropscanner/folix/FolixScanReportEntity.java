package com.cropscanner.folix;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.cropscanner.folix.auth.FolixFarmerEntity;
import com.cropscanner.folix.location.FolixRegionCell;
import com.cropscanner.model.DiseaseInfo;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Persisted record of one completed Gemini diagnosis:
 *
 *   Gemini diagnosis ({@link DiagnosisResult}) → FOLIX Scan Report → database
 *
 * This is a dedicated FOLIX table, deliberately separate from the legacy
 * CropGuard {@code scan_record} / authority entities (see
 * {@code com.cropscanner.entity}) — it is not read or written by any legacy
 * code, and legacy tables are not read or written by anything here.
 *
 * Instances are created once, at save time, from a validated
 * {@link FolixReportRequest} (see {@link FolixReportService}) and are
 * otherwise immutable in this stage: nothing in Stage 5A updates a report
 * after it is saved.
 *
 * Stage 6B: every report belongs to exactly one {@link FolixFarmerEntity} (the farmer who saved
 * it). The owner is set once, at creation, from the server-side authenticated session — never from
 * anything the browser sends — and cannot be changed afterwards ({@code updatable = false}). The
 * owner is intentionally NOT part of {@link FolixReportResponse}, so it never crosses the API.
 *
 * Reports saved before Stage 6B have no owner (see {@code backend/db/stage6b-report-ownership.sql});
 * every read path filters by owner, so such legacy rows are simply invisible to every farmer.
 *
 * Stage 6C: a report MAY carry a coarse region ({@code regionLatMdeg}/{@code regionLngMdeg} — the centre of a grid
 * cell, in millidegrees), attached at save time only if the owning farmer had explicitly enabled location sharing.
 * Both are null otherwise ("location unavailable"), which is the case for every report saved before Stage 6C and
 * for every farmer who never opted in — nothing is ever inferred or back-filled. The region is never part of
 * {@link FolixReportResponse}; only the aggregated Disease Map reads it (see {@code folix.map}). There is no
 * exact-coordinate field. The region is cleared, not the report, if the farmer later withdraws consent.
 */
@Entity
@Table(name = "folix_scan_report", indexes = {
        @Index(name = "idx_folix_scan_report_created_at", columnList = "createdAt"),
        @Index(name = "idx_folix_scan_report_needs_review", columnList = "needsHumanReview"),
        @Index(name = "idx_folix_scan_report_farmer", columnList = "farmer_id"),
        @Index(name = "idx_folix_scan_report_region", columnList = "region_lat_mdeg, region_lng_mdeg, created_at")
})
public class FolixScanReportEntity {

    @Id
    private UUID id;

    /** The farmer who owns this report. Set once from the authenticated session; never exposed by any DTO. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "farmer_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_folix_scan_report_farmer"))
    private FolixFarmerEntity farmer;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private String crop;

    @Column(nullable = false)
    private String plantPart;

    @Column(nullable = false)
    private String disease;

    @Column(nullable = false)
    private String severity;

    @Column(nullable = false)
    private String confidence;

    @Convert(converter = StringListJsonConverter.class)
    @Column(columnDefinition = "TEXT")
    private List<String> symptoms;

    @Column(columnDefinition = "TEXT")
    private String reasoning;

    @Convert(converter = StringListJsonConverter.class)
    @Column(columnDefinition = "TEXT")
    private List<String> recommendedActions;

    @Column(nullable = false)
    private boolean needsHumanReview;

    /** Organic/chemical treatment guidance for the diagnosed disease, or null if none was found. Never fabricated. */
    @Convert(converter = RemedyPlanJsonConverter.class)
    @Column(columnDefinition = "TEXT")
    private DiseaseInfo.RemedyPlan remedyPlan;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private FolixReportStatus status;

    /**
     * Stage 6H: whether the diagnosed disease itself has a high potential to spread to nearby
     * crops/farms — distinct from {@code severity}, which describes only how badly THIS plant is
     * affected. Sourced from the same maintained epidemic-risk lookup table used by the live Scanner
     * ({@code DiagnosisResult.epidemicRisk}), never inferred from severity. Defaults to false for
     * reports saved before Stage 6H.
     */
    @Column(nullable = false)
    private boolean epidemicRisk;

    /**
     * Visible severity as a 0-100 percentage (the share of tissue affected, as reported by the scan), or null
     * when it was not available (older reports, unknown disease, healthy plant). Never backfilled.
     */
    @Column(name = "severity_percent")
    private Integer severityPercent;

    /** Estimated chance of spreading, 0-100 (see SpreadChanceEstimator), or null when not available. Never backfilled. */
    @Column(name = "spread_chance_percent")
    private Integer spreadChancePercent;

    /** Stage 6C: centre of the coarse region (millidegrees), or null = location unavailable. Never exact coordinates. */
    @Column(name = "region_lat_mdeg")
    private Integer regionLatMdeg;

    @Column(name = "region_lng_mdeg")
    private Integer regionLngMdeg;

    protected FolixScanReportEntity() {
        // JPA only
    }

    public FolixScanReportEntity(UUID id, FolixFarmerEntity farmer, Instant createdAt, String crop, String plantPart, String disease,
                                  String severity, String confidence, List<String> symptoms, String reasoning,
                                  List<String> recommendedActions, boolean needsHumanReview,
                                  DiseaseInfo.RemedyPlan remedyPlan, FolixReportStatus status, boolean epidemicRisk) {
        this.id = Objects.requireNonNull(id, "id");
        this.farmer = Objects.requireNonNull(farmer, "farmer");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.crop = Objects.requireNonNull(crop, "crop");
        this.plantPart = Objects.requireNonNull(plantPart, "plantPart");
        this.disease = Objects.requireNonNull(disease, "disease");
        this.severity = Objects.requireNonNull(severity, "severity");
        this.confidence = Objects.requireNonNull(confidence, "confidence");
        this.symptoms = symptoms == null ? List.of() : List.copyOf(symptoms);
        this.reasoning = reasoning;
        this.recommendedActions = recommendedActions == null ? List.of() : List.copyOf(recommendedActions);
        this.needsHumanReview = needsHumanReview;
        this.remedyPlan = remedyPlan;
        this.status = Objects.requireNonNull(status, "status");
        this.epidemicRisk = epidemicRisk;
    }

    /**
     * Compatibility constructor for callers built before {@code epidemicRisk} existed (older tests).
     * Defaults it to false — the same "not flagged" default {@link DiagnosisResult} itself uses.
     */
    public FolixScanReportEntity(UUID id, FolixFarmerEntity farmer, Instant createdAt, String crop, String plantPart, String disease,
                                  String severity, String confidence, List<String> symptoms, String reasoning,
                                  List<String> recommendedActions, boolean needsHumanReview,
                                  DiseaseInfo.RemedyPlan remedyPlan, FolixReportStatus status) {
        this(id, farmer, createdAt, crop, plantPart, disease, severity, confidence, symptoms, reasoning,
                recommendedActions, needsHumanReview, remedyPlan, status, false);
    }

    /**
     * Compatibility constructor for callers built before {@code remedyPlan}/{@code epidemicRisk} existed
     * (older tests). Defaults both to their "nothing found/not flagged" values — the same defaults used
     * everywhere else in this feature.
     */
    public FolixScanReportEntity(UUID id, FolixFarmerEntity farmer, Instant createdAt, String crop, String plantPart, String disease,
                                  String severity, String confidence, List<String> symptoms, String reasoning,
                                  List<String> recommendedActions, boolean needsHumanReview,
                                  FolixReportStatus status) {
        this(id, farmer, createdAt, crop, plantPart, disease, severity, confidence, symptoms, reasoning,
                recommendedActions, needsHumanReview, null, status, false);
    }

    /** Attaches the scan's percentages (each clamped to 0-100, or null). Called once, at save time. */
    public void assignPercentages(Integer severityPercent, Integer spreadChancePercent) {
        this.severityPercent = clampPercent(severityPercent);
        this.spreadChancePercent = clampPercent(spreadChancePercent);
    }

    private static Integer clampPercent(Integer v) {
        return v == null ? null : Math.max(0, Math.min(100, v));
    }

    /** Attaches the farmer's opted-in coarse region. Called once, at save time, before the report is persisted. */
    public void assignRegion(FolixRegionCell cell) {
        this.regionLatMdeg = Objects.requireNonNull(cell, "cell").latMdeg();
        this.regionLngMdeg = cell.lngMdeg();
    }

    /** Removes the region (consent withdrawn). The report itself is otherwise unchanged. */
    public void clearRegion() {
        this.regionLatMdeg = null;
        this.regionLngMdeg = null;
    }

    public UUID getId() { return id; }
    /** The owning farmer (a lazy reference — do not serialize). */
    public FolixFarmerEntity getFarmer() { return farmer; }
    public Instant getCreatedAt() { return createdAt; }
    public String getCrop() { return crop; }
    public String getPlantPart() { return plantPart; }
    public String getDisease() { return disease; }
    public String getSeverity() { return severity; }
    public String getConfidence() { return confidence; }
    public List<String> getSymptoms() { return symptoms; }
    public String getReasoning() { return reasoning; }
    public List<String> getRecommendedActions() { return recommendedActions; }
    public boolean isNeedsHumanReview() { return needsHumanReview; }
    /** Treatment guidance for the diagnosed disease, or null if none was found for it. Never fabricated. */
    public DiseaseInfo.RemedyPlan getRemedyPlan() { return remedyPlan; }
    public FolixReportStatus getStatus() { return status; }
    /** Epidemic/spread danger for the diagnosed disease — never the same thing as {@code severity}. */
    public boolean isEpidemicRisk() { return epidemicRisk; }
    public void markEpidemicRisk(boolean epidemicRisk) { this.epidemicRisk = epidemicRisk; }
    /** Visible severity 0-100, or null when not available. */
    public Integer getSeverityPercent() { return severityPercent; }
    /** Estimated spread chance 0-100, or null when not available. */
    public Integer getSpreadChancePercent() { return spreadChancePercent; }
    /** Coarse region centre (millidegrees), or null when no location was shared for this report. */
    public Integer getRegionLatMdeg() { return regionLatMdeg; }
    public Integer getRegionLngMdeg() { return regionLngMdeg; }
    public boolean hasRegion() { return regionLatMdeg != null && regionLngMdeg != null; }
}
