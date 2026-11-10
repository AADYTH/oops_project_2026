package com.cropscanner.model;

/**
 * Gemini's independent second opinion on a leaf photo, per the CropGuard
 * architecture: "Gemini is a second visual opinion, not an unquestionable
 * authority." This is intentionally a separate type from {@link DiseaseInfo}
 * rather than a set of fields Gemini can overwrite — see
 * {@code EvidenceConfidenceService}, which is the ONLY place Gemini's
 * opinion is allowed to influence the final result, and even there it only
 * nudges {@code confidenceLevel} up or down. It never changes
 * {@code cropName}/{@code diseaseName}, never touches the remedy plan, and
 * is completely absent (not defaulted to some in-between guess) when Gemini
 * wasn't consulted or wasn't available.
 *
 * @param requested       true if a Gemini call was attempted at all for this scan
 *                        (false when Gemini is disabled by config, or offline)
 * @param available       true if a configured key was available AND Gemini returned a usable answer.
 *                        false means "requested but unavailable" — every configured key was
 *                        rate-limited/erroring, or Gemini was disabled/offline; the caller must fall
 *                        back to the local model, never fabricate an opinion here.
 * @param cropName        Gemini's own guess at the crop, or null if unavailable
 * @param diseaseName     Gemini's own guess at the disease, or null if unavailable
 * @param visualEvidence  Gemini's natural-language description of the symptoms it saw, for the
 *                        "ambiguous case" explanation use described in the architecture doc; null if unavailable
 * @param agreesWithLocalModel true/false once both opinions exist and can be compared; null when
 *                        Gemini's opinion isn't available to compare against
 * @param unavailableReason human-readable reason Gemini wasn't consulted/available, for logs and (optionally)
 *                        a "why no second opinion" UI note — never contains a raw API key or exception stack
 */
public record GeminiOpinion(
        boolean requested,
        boolean available,
        String cropName,
        String diseaseName,
        String visualEvidence,
        Boolean agreesWithLocalModel,
        String unavailableReason
) {
    public static GeminiOpinion notRequested(String reason) {
        return new GeminiOpinion(false, false, null, null, null, null, reason);
    }

    public static GeminiOpinion unavailable(String reason) {
        return new GeminiOpinion(true, false, null, null, null, null, reason);
    }
}
