package com.cropscanner.dto;

import com.cropscanner.model.DiseaseInfo;
import com.cropscanner.model.GeminiOpinion;
import com.cropscanner.model.ScanAnalysisResult;

import java.time.Instant;
import java.util.UUID;

/**
 * Flat JSON shape returned to the frontend. Kept separate from
 * {@link ScanAnalysisResult} so the wire format can evolve independently of
 * the internal domain model.
 */
public record ScanResponse(
        UUID scanId,
        Instant analyzedAt,
        String cropName,
        String diseaseName,
        String affectedPlantPart,
        double confidencePercent,
        double severityPercent,
        boolean isEpidemicRisk,
        boolean severe,
        DiseaseInfo.RemedyPlan remedyPlan,
        String escalationStatus,
        String notifiedAuthorityName,
        String notifiedAuthorityPhone,
        String notifiedAuthorityEmail,
        Double notifiedAuthorityDistanceKm,
        String confidenceLevel,
        boolean lowConfidence,
        java.util.List<DiseaseInfo.AlternativePrediction> alternatives,
        boolean partSupported,
        String partWarning,
        java.util.List<DiseaseInfo.ModelVote> modelPanel,
        DiseaseInfo.Consensus consensus,
        GeminiOpinion geminiOpinion
) {

    public static ScanResponse from(ScanAnalysisResult result) {
        DiseaseInfo d = result.diseaseInfo();
        String status;
        String authorityName = null;
        String authorityPhone = null;
        String authorityEmail = null;
        Double authorityDistance = null;

        switch (result.escalationOutcome()) {
            case ScanAnalysisResult.EscalationOutcome.NotRequired ignored -> status = "NOT_REQUIRED";
            case ScanAnalysisResult.EscalationOutcome.AuthorityNotified n -> {
                status = "AUTHORITY_NOTIFIED";
                authorityName = n.authority().name();
                authorityPhone = n.authority().phoneNumber();
                authorityEmail = n.authority().email();
                authorityDistance = n.authority().distanceKm();
            }
            case ScanAnalysisResult.EscalationOutcome.SmsNotified n -> {
                status = "AUTHORITY_NOTIFIED_BY_SMS";
                authorityName = n.authority().name();
                authorityPhone = n.authority().phoneNumber();
                authorityEmail = n.authority().email();
                authorityDistance = n.authority().distanceKm();
            }
            case ScanAnalysisResult.EscalationOutcome.NoAuthorityFound n -> status = "NO_AUTHORITY_FOUND: " + n.reason();
            case ScanAnalysisResult.EscalationOutcome.NotificationFailed n -> {
                status = "NOTIFICATION_FAILED: " + n.reason();
                authorityName = n.authority().name();
                authorityPhone = n.authority().phoneNumber();
                authorityEmail = n.authority().email();
            }
        }

        return new ScanResponse(
                result.scanId(),
                result.analyzedAt(),
                d.cropName(),
                d.diseaseName(),
                d.affectedPlantPart().name(),
                d.confidencePercent(),
                d.severityPercent(),
                d.isEpidemicRisk(),
                result.severe(),
                d.remedyPlan(),
                status,
                authorityName,
                authorityPhone,
                authorityEmail,
                authorityDistance,
                d.confidenceLevel().name(),
                d.confidenceLevel() == DiseaseInfo.ConfidenceLevel.LOW,
                d.alternatives(),
                d.partSupported(),
                d.partWarning(),
                d.modelPanel(),
                d.consensus(),
                d.geminiOpinion()
        );
    }
}
