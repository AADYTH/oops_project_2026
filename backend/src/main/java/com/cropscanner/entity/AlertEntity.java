package com.cropscanner.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A severe-scan alert dispatched to a specific authority, persisted so the
 * authority-side dashboard (Tier 3 upgrade #9) can list, acknowledge and
 * resolve alerts instead of them existing only as a fire-and-forget webhook
 * call or a log line.
 */
@Entity
@Table(name = "alert", indexes = {
        @Index(name = "idx_alert_authority_id", columnList = "authorityId"),
        @Index(name = "idx_alert_status", columnList = "status")
})
public class AlertEntity {

    public enum Channel { WEBHOOK, SMS, FAILED }
    public enum Status { NOTIFIED, ACKNOWLEDGED, RESOLVED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private UUID scanId;

    @Column(nullable = false)
    private Long authorityId;

    @Column(nullable = false)
    private String cropName;

    @Column(nullable = false)
    private String diseaseName;

    private double severityPercent;
    private boolean epidemicRisk;

    private Double latitude;
    private Double longitude;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Channel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.NOTIFIED;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant updatedAt;

    @Column(length = 1000)
    private String resolutionNote;

    protected AlertEntity() {
    }

    public AlertEntity(UUID scanId, Long authorityId, String cropName, String diseaseName,
                        double severityPercent, boolean epidemicRisk, Double latitude, Double longitude,
                        Channel channel) {
        this.scanId = scanId;
        this.authorityId = authorityId;
        this.cropName = cropName;
        this.diseaseName = diseaseName;
        this.severityPercent = severityPercent;
        this.epidemicRisk = epidemicRisk;
        this.latitude = latitude;
        this.longitude = longitude;
        this.channel = channel;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public UUID getScanId() { return scanId; }
    public Long getAuthorityId() { return authorityId; }
    public String getCropName() { return cropName; }
    public String getDiseaseName() { return diseaseName; }
    public double getSeverityPercent() { return severityPercent; }
    public boolean isEpidemicRisk() { return epidemicRisk; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }
    public Channel getChannel() { return channel; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getResolutionNote() { return resolutionNote; }

    public void updateStatus(Status status, String resolutionNote) {
        this.status = status;
        this.resolutionNote = resolutionNote;
        this.updatedAt = Instant.now();
    }
}
