package com.cropscanner.folix;

import com.cropscanner.model.DiseaseInfo;

import java.util.List;

/**
 * What is needed to persist one FOLIX scan report. Mirrors the
 * {@link DiagnosisResult} contract fields (not {@code plant_detected}, which
 * is a Scanner-response-only concept and is not persisted) so a report can
 * be built directly from a completed Gemini diagnosis via {@link #from}.
 *
 * This is a plain data holder — {@link FolixReportService} is the only place
 * that decides whether the data is valid. No JPA entity is ever accepted or
 * returned across this boundary.
 */
public record FolixReportRequest(
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
        boolean epidemicRisk,
        Integer severityPercent,
        Integer spreadChancePercent
) {

    public FolixReportRequest {
        symptoms = symptoms == null ? List.of() : List.copyOf(symptoms);
        recommendedActions = recommendedActions == null ? List.of() : List.copyOf(recommendedActions);
    }



    /**
     * Compatibility constructor for callers built before {@code remedyPlan}/{@code epidemicRisk} existed
     * (older tests). Defaults to null/false — the same "nothing found yet" defaults {@link DiagnosisResult}
     * itself uses.
     */
    public FolixReportRequest(String crop, String plantPart, String disease, String severity, String confidence,
                               List<String> symptoms, String reasoning, List<String> recommendedActions,
                               boolean needsHumanReview) {
        this(crop, plantPart, disease, severity, confidence, symptoms, reasoning, recommendedActions,
                needsHumanReview, null, false, null, null);
    }

    /** Compatibility constructor for callers built before the two percentages existed (both default to null). */
    public FolixReportRequest(String crop, String plantPart, String disease, String severity, String confidence,
                               List<String> symptoms, String reasoning, List<String> recommendedActions,
                               boolean needsHumanReview, DiseaseInfo.RemedyPlan remedyPlan, boolean epidemicRisk) {
        this(crop, plantPart, disease, severity, confidence, symptoms, reasoning, recommendedActions,
                needsHumanReview, remedyPlan, epidemicRisk, null, null);
    }

    /** Builds the persistence request directly from a completed Gemini diagnosis. */
    public static FolixReportRequest from(DiagnosisResult result) {
        return new FolixReportRequest(
                result.crop(),
                result.plantPart().wire(),
                result.disease(),
                result.severity().wire(),
                result.confidence().wire(),
                result.symptoms(),
                result.reasoning(),
                result.recommendedActions(),
                result.needsHumanReview(),
                result.remedyPlan(),
                result.epidemicRisk(),
                result.severityPercent(),
                result.spreadChancePercent()
        );
    }
}
