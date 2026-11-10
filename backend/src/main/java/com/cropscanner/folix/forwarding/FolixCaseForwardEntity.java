package com.cropscanner.folix.forwarding;

import com.cropscanner.folix.FolixScanReportEntity;
import com.cropscanner.folix.agrinetwork.FolixSupportContactEntity;
import jakarta.persistence.Column;
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
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Stage 6E — the record that one saved scan report was manually forwarded by its owning farmer to one
 * Agricultural Network support contact. One row per report at most (unique {@code report_id}).
 *
 * Deliberately minimal: the report, the chosen contact, a status and the timestamp. There is no farmer
 * column — ownership is always derived through the report, and the report itself is not modified.
 * Immutable after creation.
 */
@Entity
@Table(name = "folix_case_forward",
        uniqueConstraints = @UniqueConstraint(name = "uq_folix_case_forward_report", columnNames = "report_id"),
        indexes = @Index(name = "idx_folix_case_forward_contact", columnList = "support_contact_id"))
public class FolixCaseForwardEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_folix_case_forward_report"))
    private FolixScanReportEntity report;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "support_contact_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_folix_case_forward_contact"))
    private FolixSupportContactEntity supportContact;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private FolixForwardStatus status;

    @Column(name = "forwarded_at", nullable = false, updatable = false)
    private Instant forwardedAt;

    protected FolixCaseForwardEntity() {
        // JPA only
    }

    public FolixCaseForwardEntity(UUID id, FolixScanReportEntity report, FolixSupportContactEntity supportContact,
                                   FolixForwardStatus status, Instant forwardedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.report = Objects.requireNonNull(report, "report");
        this.supportContact = Objects.requireNonNull(supportContact, "supportContact");
        this.status = Objects.requireNonNull(status, "status");
        this.forwardedAt = Objects.requireNonNull(forwardedAt, "forwardedAt");
    }

    public UUID getId() { return id; }
    public FolixScanReportEntity getReport() { return report; }
    public FolixSupportContactEntity getSupportContact() { return supportContact; }
    public FolixForwardStatus getStatus() { return status; }
    public Instant getForwardedAt() { return forwardedAt; }
}
