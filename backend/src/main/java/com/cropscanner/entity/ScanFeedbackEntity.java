package com.cropscanner.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A user- or agronomist-submitted correction to a scan's diagnosis (Tier 1
 * upgrade #2). This is the raw material for building a real labelled
 * dataset from field conditions: every row here is a (photo, model-guess,
 * ground-truth) triple you can export and use to fine-tune the classifier.
 *
 * IMPORTANT — this table stores corrections, it does NOT retrain the model.
 * Automatic retraining is a separate, deliberately-not-automated MLOps
 * pipeline: export this table periodically, review it (crowd corrections
 * can themselves be wrong), and use it as additional fine-tuning data for
 * the Python inference service on your own schedule.
 */
@Entity
@Table(name = "scan_feedback")
public class ScanFeedbackEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private UUID scanId;

    @Column(nullable = false)
    private String originalCropName;

    @Column(nullable = false)
    private String originalDiseaseName;

    private double originalConfidencePercent;

    @Column(nullable = false)
    private boolean wasCorrect;

    private String correctedCropName;
    private String correctedDiseaseName;

    @Column(length = 1000)
    private String note;

    @Column(nullable = false)
    private Instant submittedAt;

    protected ScanFeedbackEntity() {
    }

    public ScanFeedbackEntity(UUID scanId, String originalCropName, String originalDiseaseName,
                               double originalConfidencePercent, boolean wasCorrect,
                               String correctedCropName, String correctedDiseaseName, String note) {
        this.scanId = scanId;
        this.originalCropName = originalCropName;
        this.originalDiseaseName = originalDiseaseName;
        this.originalConfidencePercent = originalConfidencePercent;
        this.wasCorrect = wasCorrect;
        this.correctedCropName = correctedCropName;
        this.correctedDiseaseName = correctedDiseaseName;
        this.note = note;
        this.submittedAt = Instant.now();
    }

    public Long getId() { return id; }
    public UUID getScanId() { return scanId; }
    public String getOriginalCropName() { return originalCropName; }
    public String getOriginalDiseaseName() { return originalDiseaseName; }
    public double getOriginalConfidencePercent() { return originalConfidencePercent; }
    public boolean isWasCorrect() { return wasCorrect; }
    public String getCorrectedCropName() { return correctedCropName; }
    public String getCorrectedDiseaseName() { return correctedDiseaseName; }
    public String getNote() { return note; }
    public Instant getSubmittedAt() { return submittedAt; }
}
