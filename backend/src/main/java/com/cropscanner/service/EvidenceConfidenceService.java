package com.cropscanner.service;

import com.cropscanner.model.DiseaseInfo;
import com.cropscanner.model.GeminiOpinion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Combines the local model's verdict with Gemini's optional second opinion,
 * per README "Combining local AI + Gemini" / "Confidence system":
 *
 *   - Gemini unavailable (disabled, offline, every key exhausted) -> use the
 *     local model's confidence UNCHANGED. Gemini's absence is never itself
 *     evidence of anything.
 *   - Strong agreement (same crop AND same disease, independently) -> the
 *     local confidence level may move up one tier (capped at HIGH).
 *   - Disagreement (different disease, or Gemini couldn't tell) -> the local
 *     confidence level moves down one tier (floored at LOW) — never
 *     silently kept as if nothing happened, and never forced to some
 *     "average" that would misrepresent a real disagreement as false
 *     precision.
 *
 * This class NEVER changes {@code cropName}/{@code diseaseName} — those
 * stay exactly what the local model panel decided. Gemini only ever nudges
 * how confident CropGuard says it is in that answer, exactly as the
 * architecture spec requires ("Gemini must not... override the local model
 * blindly").
 */
@Service
public class EvidenceConfidenceService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceConfidenceService.class);

    public record CombinedResult(DiseaseInfo.ConfidenceLevel confidenceLevel, GeminiOpinion geminiOpinion) {
    }

    public CombinedResult combine(
            DiseaseInfo.ConfidenceLevel localConfidence,
            String localCropName,
            String localDiseaseName,
            GeminiOpinion geminiOpinion) {

        if (geminiOpinion == null || !geminiOpinion.available()) {
            // Not requested, disabled, offline, or every key exhausted — local result stands as-is.
            return new CombinedResult(localConfidence, geminiOpinion);
        }

        boolean agrees = agrees(localCropName, localDiseaseName, geminiOpinion.cropName(), geminiOpinion.diseaseName());
        GeminiOpinion annotated = new GeminiOpinion(
                geminiOpinion.requested(), geminiOpinion.available(), geminiOpinion.cropName(),
                geminiOpinion.diseaseName(), geminiOpinion.visualEvidence(), agrees, geminiOpinion.unavailableReason());

        DiseaseInfo.ConfidenceLevel adjusted = agrees ? bumpUp(localConfidence) : bumpDown(localConfidence);

        log.info("Evidence combination: local='{} / {}' ({}) vs Gemini='{} / {}' -> agreement={}, "
                        + "confidence {} -> {}",
                localCropName, localDiseaseName, localConfidence,
                geminiOpinion.cropName(), geminiOpinion.diseaseName(), agrees, localConfidence, adjusted);

        return new CombinedResult(adjusted, annotated);
    }

    /**
     * Loose, case-insensitive comparison rather than exact string equality
     * — the local panel and Gemini describe diseases in their own words
     * ("Late Blight (Phytophthora infestans)" vs. "late blight"), and
     * requiring exact matches would report a real agreement as a
     * disagreement purely over phrasing.
     */
    private boolean agrees(String localCrop, String localDisease, String geminiCrop, String geminiDisease) {
        if (geminiCrop == null || geminiDisease == null) {
            return false; // Gemini couldn't tell -> not a confirmed agreement, treated as disagreement below
        }
        boolean healthyMatch = containsIgnoreCase(localDisease, "healthy") && containsIgnoreCase(geminiDisease, "healthy");
        if (healthyMatch) {
            return true;
        }
        boolean cropMatches = fuzzyContains(localCrop, geminiCrop);
        boolean diseaseMatches = fuzzyContains(localDisease, geminiDisease);
        return cropMatches && diseaseMatches;
    }

    private boolean fuzzyContains(String a, String b) {
        if (a == null || b == null) return false;
        String na = normalize(a);
        String nb = normalize(b);
        if (na.isBlank() || nb.isBlank()) return false;
        return na.contains(nb) || nb.contains(na);
    }

    private String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    private boolean containsIgnoreCase(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
    }

    private DiseaseInfo.ConfidenceLevel bumpUp(DiseaseInfo.ConfidenceLevel level) {
        return switch (level) {
            case LOW -> DiseaseInfo.ConfidenceLevel.MEDIUM;
            case MEDIUM, HIGH -> DiseaseInfo.ConfidenceLevel.HIGH;
        };
    }

    private DiseaseInfo.ConfidenceLevel bumpDown(DiseaseInfo.ConfidenceLevel level) {
        return switch (level) {
            case HIGH -> DiseaseInfo.ConfidenceLevel.MEDIUM;
            case MEDIUM, LOW -> DiseaseInfo.ConfidenceLevel.LOW;
        };
    }
}
