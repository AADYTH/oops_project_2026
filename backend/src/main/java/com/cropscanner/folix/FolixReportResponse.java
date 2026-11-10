package com.cropscanner.folix;

import com.cropscanner.model.DiseaseInfo;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Wire shape for one persisted FOLIX scan report. Kept separate from
 * {@link FolixScanReportEntity} so the JPA entity is never exposed directly
 * across a (future) API boundary and its shape can evolve independently of
 * the storage model.
 */
public record FolixReportResponse(
        UUID id,
        Instant createdAt,
        String crop,
        String plantPart,
        String disease,
        String severity,
        String confidence,
        List<String> symptoms,
        String reasoning,
        List<String> recommendedActions,
        boolean needsHumanReview,
        DiseaseInfo.RemedyPlan remedyPlan,
        String status,
        boolean epidemicRisk,
        Integer severityPercent,
        Integer spreadChancePercent
) {

    public static FolixReportResponse from(FolixScanReportEntity e) {
        return new FolixReportResponse(
                e.getId(),
                e.getCreatedAt(),
                e.getCrop(),
                e.getPlantPart(),
                e.getDisease(),
                e.getSeverity(),
                e.getConfidence(),
                e.getSymptoms(),
                e.getReasoning(),
                e.getRecommendedActions(),
                e.isNeedsHumanReview(),
                e.getRemedyPlan(),
                e.getStatus().name(),
                e.isEpidemicRisk(),
                e.getSeverityPercent(),
                e.getSpreadChancePercent()
        );
    }
}
