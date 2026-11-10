package com.cropscanner.dto;

import com.cropscanner.entity.AlertEntity;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

/**
 * Request/response shapes for the authority self-service dashboard
 * (Tier 3 upgrade #9). Grouped in one file since each is small and they're
 * only ever used together by {@code AuthorityController}.
 */
public class AuthorityDtos {

    public record RegistrationRequest(
            @NotBlank String name,
            @NotBlank String district,
            @NotBlank String state,
            @NotBlank String rawAddress,
            Double latitude,
            Double longitude,
            String webhookUrl,
            String phoneNumber,
            String email
    ) {}

    /** apiKey is returned ONLY in this response — it is not retrievable again, mirroring standard API-key UX. */
    public record RegistrationResponse(Long authorityId, String apiKey, boolean geocoded, String message) {}

    public record ContactUpdateRequest(String webhookUrl, String phoneNumber, String email) {}

    public record AlertStatusUpdateRequest(@NotBlank String status, String note) {}

    public record AlertView(
            Long id,
            java.util.UUID scanId,
            String cropName,
            String diseaseName,
            double severityPercent,
            boolean epidemicRisk,
            Double latitude,
            Double longitude,
            String channel,
            String status,
            Instant createdAt,
            Instant updatedAt,
            String resolutionNote
    ) {
        public static AlertView from(AlertEntity e) {
            return new AlertView(e.getId(), e.getScanId(), e.getCropName(), e.getDiseaseName(),
                    e.getSeverityPercent(), e.isEpidemicRisk(), e.getLatitude(), e.getLongitude(),
                    e.getChannel().name(), e.getStatus().name(), e.getCreatedAt(), e.getUpdatedAt(),
                    e.getResolutionNote());
        }
    }

    /**
     * Stage 6E Part 2B — one row of the authority-side "forwarded cases" list: a FOLIX report a farmer has
     * manually forwarded (see {@code com.cropscanner.folix.forwarding}), reduced to what is actually useful
     * here. {@code reportId} doubles as the farmer/report reference — FOLIX reports deliberately never expose
     * the owning farmer's identity across this API (see {@code FolixReportResponse}), so this view doesn't
     * either; it is read-only and carries no case-management state beyond what Stage 6E Part 1 recorded.
     */
    public record ForwardedCaseView(
            java.util.UUID reportId,
            String crop,
            String disease,
            String severity,
            Instant forwardedAt,
            String status,
            Long supportContactId,
            String supportContactName,
            String supportContactOrganization
    ) {
        public static ForwardedCaseView from(com.cropscanner.folix.forwarding.FolixCaseForwardEntity e) {
            return new ForwardedCaseView(
                    e.getReport().getId(),
                    e.getReport().getCrop(),
                    e.getReport().getDisease(),
                    e.getReport().getSeverity(),
                    e.getForwardedAt(),
                    e.getStatus().name(),
                    e.getSupportContact().getId(),
                    e.getSupportContact().getName(),
                    e.getSupportContact().getOrganization());
        }
    }

    /** The full report content behind one forwarded case, for "view forwarded report details". */
    public record ForwardedCaseDetail(
            java.util.UUID reportId,
            Instant reportCreatedAt,
            String crop,
            String plantPart,
            String disease,
            String severity,
            String confidence,
            java.util.List<String> symptoms,
            String reasoning,
            java.util.List<String> recommendedActions,
            boolean needsHumanReview,
            String reportStatus,
            Instant forwardedAt,
            String forwardStatus,
            Long supportContactId,
            String supportContactName,
            String supportContactOrganization
    ) {
        public static ForwardedCaseDetail from(com.cropscanner.folix.forwarding.FolixCaseForwardEntity e) {
            var report = e.getReport();
            var contact = e.getSupportContact();
            return new ForwardedCaseDetail(
                    report.getId(), report.getCreatedAt(), report.getCrop(), report.getPlantPart(),
                    report.getDisease(), report.getSeverity(), report.getConfidence(), report.getSymptoms(),
                    report.getReasoning(), report.getRecommendedActions(), report.isNeedsHumanReview(),
                    report.getStatus().name(), e.getForwardedAt(), e.getStatus().name(),
                    contact.getId(), contact.getName(), contact.getOrganization());
        }
    }

    public record AuthorityFarmerView(
            String id,
            java.util.UUID rawId,
            String name,
            String email,
            String phone,
            String village,
            String district,
            java.util.List<String> crops,
            String status,
            String statusBadge,
            String lastReport,
            Instant registeredAt
    ) {
        public AuthorityFarmerView(String id, String name, String email, String phone, String village, String district,
                                   java.util.List<String> crops, String status, String statusBadge, String lastReport, Instant registeredAt) {
            this(id, null, name, email, phone, village, district, crops, status, statusBadge, lastReport, registeredAt);
        }
    }

    public record FarmerStatusUpdateRequest(String status) {}

    public record AuthorityBroadcastResponse(
            boolean success,
            int farmersAlerted,
            String message
    ) {}

    public record NewBroadcastAlertRequest(
            @NotBlank String diseaseName,
            @NotBlank String cropName,
            Double severityPercent,
            String advisory
    ) {}
}
