package com.cropscanner.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Treatment-tracking reminder (Tier 3 upgrade #10): "reapply the fungicide
 * on day 7" or "pre-harvest interval ends on day 14 — safe to harvest
 * after this date". Generated automatically from the {@code RemedyPlan}
 * attached to a scan's diagnosis at scan time (see
 * {@code ScanPersistenceService}), so the data already produced by the
 * remedy knowledge base just needs a notification layer, per the original
 * roadmap note.
 */
@Entity
@Table(name = "reminder", indexes = {
        @Index(name = "idx_reminder_due_at", columnList = "dueAt"),
        @Index(name = "idx_reminder_scan_id", columnList = "scanId")
})
public class ReminderEntity {

    public enum ReminderType { REAPPLICATION, PRE_HARVEST_INTERVAL_END }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private UUID scanId;

    @Column(nullable = false)
    private String cropName;

    @Column(nullable = false)
    private String diseaseName;

    @Column(nullable = false)
    private String remedyName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReminderType type;

    @Column(nullable = false)
    private Instant dueAt;

    @Column(nullable = false)
    private boolean completed = false;

    protected ReminderEntity() {
    }

    public ReminderEntity(UUID scanId, String cropName, String diseaseName, String remedyName,
                           ReminderType type, Instant dueAt) {
        this.scanId = scanId;
        this.cropName = cropName;
        this.diseaseName = diseaseName;
        this.remedyName = remedyName;
        this.type = type;
        this.dueAt = dueAt;
    }

    public Long getId() { return id; }
    public UUID getScanId() { return scanId; }
    public String getCropName() { return cropName; }
    public String getDiseaseName() { return diseaseName; }
    public String getRemedyName() { return remedyName; }
    public ReminderType getType() { return type; }
    public Instant getDueAt() { return dueAt; }
    public boolean isCompleted() { return completed; }
    public void markCompleted() { this.completed = true; }
}
