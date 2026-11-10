package com.cropscanner.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public class FeedbackDtos {

    public record SubmitFeedbackRequest(
            @NotNull UUID scanId,
            @NotNull String originalCropName,
            @NotNull String originalDiseaseName,
            double originalConfidencePercent,
            @NotNull Boolean wasCorrect,
            String correctedCropName,
            String correctedDiseaseName,
            String note
    ) {}

    public record FeedbackAck(Long feedbackId, String message) {}
}
