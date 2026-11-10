package com.cropscanner.folix;

import com.cropscanner.folix.DiagnosisResult.Confidence;
import com.cropscanner.folix.DiagnosisResult.PlantPart;
import com.cropscanner.folix.DiagnosisResult.Severity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns the model's text into a {@link DiagnosisResult} — or refuses to.
 *
 * Structured output makes the model's JSON usually well-formed, but Google's own
 * guidance is to validate values in the application, and for a diagnostic tool
 * "usually" is not enough. This class is the trust boundary between an
 * unreliable model and the farmer's screen.
 *
 * Two rules govern everything here:
 *
 *  1. MISSING or WRONG-TYPED required data is an error, never a guess. If the
 *     response cannot be reliably parsed we throw {@code ANALYSIS_FAILED}; we do
 *     not invent a diagnosis, a confidence, or a "healthy" to fill a gap.
 *
 *  2. NORMALIZATION ONLY EVER MOVES TOWARD UNCERTAINTY. When the model's fields
 *     contradict each other (e.g. "unknown" disease with "high" confidence, or
 *     low confidence without a review flag) the result is corrected in the
 *     cautious direction. Nothing here can raise confidence, upgrade severity,
 *     or turn an unknown into a named disease.
 *
 * Unexpected values in the two purely descriptive enums (plant_part, severity)
 * are normalized to "unknown" — that is the safe direction. An unexpected value
 * in {@code confidence} is an error instead, because "unknown" is not a valid
 * confidence and choosing one for the model would be inventing calibration.
 */
@Component
public class AssessmentParser {

    static final int MAX_CROP = 80;
    static final int MAX_DISEASE = 120;
    static final int MAX_REASONING = 1200;
    static final int MAX_SYMPTOMS = 8;
    static final int MAX_SYMPTOM_LENGTH = 200;

    /** Model spellings that mean "no answer". Anything here becomes the literal "unknown". */
    private static final Set<String> UNKNOWN_ALIASES = Set.of(
            "unknown", "n/a", "na", "none", "null", "no disease", "unidentified", "undetermined", "unclear",
            "uncertain", "not sure", "not identified", "cannot determine", "can't determine", "cannot be determined",
            "indeterminate");

    private final ObjectMapper mapper = new ObjectMapper();
    private final ActionSafetyPolicy actionPolicy;

    public AssessmentParser(ActionSafetyPolicy actionPolicy) {
        this.actionPolicy = actionPolicy;
    }

    public DiagnosisResult parse(String modelText) {
        JsonNode root = readRoot(modelText);

        boolean plantDetected = requireBoolean(root, "plant_detected");
        String reasoning = requireNonBlankText(root, "reasoning");
        boolean modelWantsReview = requireBoolean(root, "needs_human_review");
        String rawCrop = requireText(root, "crop");
        String rawDisease = requireText(root, "disease");
        PlantPart plantPart = descriptiveEnum(root, "plant_part", PlantPart::fromWire, PlantPart.UNKNOWN);
        Severity severity = descriptiveEnum(root, "severity", Severity::fromWire, Severity.UNKNOWN);
        Confidence confidence = requireConfidence(root);
        List<String> symptoms = requireStringArray(root, "symptoms", MAX_SYMPTOMS, MAX_SYMPTOM_LENGTH);
        List<String> actions = requireStringArray(root, "recommended_actions", 12, 600);
        Integer severityPercent = optionalPercent(root, "severity_percent");

        reasoning = ActionSafetyPolicy.truncate(ActionSafetyPolicy.collapseWhitespace(reasoning), MAX_REASONING);

        // ---- Invariant A: no plant, no diagnosis. Overrides whatever else the model claimed. ----
        if (!plantDetected) {
            return new DiagnosisResult(
                    false, PlantPart.UNKNOWN, DiagnosisResult.UNKNOWN, DiagnosisResult.UNKNOWN,
                    Severity.UNKNOWN, Confidence.LOW, List.of(), reasoning, List.of(), true);
        }

        String crop = normalizeName(rawCrop, MAX_CROP);
        String disease = normalizeName(rawDisease, MAX_DISEASE);
        boolean diseaseUnknown = DiagnosisResult.UNKNOWN.equals(disease);

        boolean needsReview = modelWantsReview;

        // ---- Invariant B: an unnamed condition cannot carry confidence, and always gets a human. ----
        if (diseaseUnknown) {
            confidence = Confidence.LOW;
            needsReview = true;
        }

        // ---- Invariant C: low confidence always means human review. ----
        if (confidence == Confidence.LOW) {
            needsReview = true;
        }

        List<String> safeActions = actionPolicy.apply(actions);

        // A percentage only means something when there is a named condition to be a percentage of.
        Integer safePercent = diseaseUnknown || SpreadChanceEstimator.isHealthy(disease) ? null : severityPercent;

        return new DiagnosisResult(
                true, plantPart, crop, disease, severity, confidence,
                symptoms, reasoning, safeActions, needsReview).withSeverityPercent(safePercent);
    }

    // ------------------------------------------------------------------
    // JSON access helpers — strict about presence and type
    // ------------------------------------------------------------------

    private JsonNode readRoot(String modelText) {
        if (modelText == null || modelText.isBlank()) {
            throw malformed("empty model output");
        }
        String json = stripCodeFence(modelText.trim());
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw malformed("model output is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw malformed("model output is not a JSON object");
        }
        return root;
    }

    /** Structured output should not emit fences, but stripping them is a harmless, safe normalization. */
    private static String stripCodeFence(String s) {
        if (!s.startsWith("```")) return s;
        int firstNewline = s.indexOf('\n');
        String inner = firstNewline < 0 ? "" : s.substring(firstNewline + 1);
        int lastFence = inner.lastIndexOf("```");
        if (lastFence >= 0) inner = inner.substring(0, lastFence);
        return inner.trim();
    }

    private static boolean requireBoolean(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || !n.isBoolean()) {
            throw malformed("field '" + field + "' missing or not a boolean");
        }
        return n.booleanValue();
    }

    private static String requireText(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || !n.isTextual()) {
            throw malformed("field '" + field + "' missing or not a string");
        }
        return n.textValue();
    }

    private static String requireNonBlankText(JsonNode root, String field) {
        String text = requireText(root, field);
        if (text.isBlank()) {
            throw malformed("field '" + field + "' is blank");
        }
        return text;
    }

    /**
     * severity_percent is OPTIONAL and purely descriptive (how much of the visible tissue is affected).
     * Absent, non-numeric or out-of-range values become null / are clamped to 0-100 — a bad number here
     * must never fail an otherwise good diagnosis, and null simply means "not available".
     */
    private static Integer optionalPercent(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || !n.isNumber()) {
            return null;
        }
        double v = n.doubleValue();
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return null;
        }
        return (int) Math.max(0, Math.min(100, Math.round(v)));
    }

    private interface WireLookup<T> {
        java.util.Optional<T> fromWire(String wire);
    }

    /** plant_part / severity: present and textual, but an out-of-set value safely becomes "unknown". */
    private static <T> T descriptiveEnum(JsonNode root, String field, WireLookup<T> lookup, T unknown) {
        String text = requireText(root, field);
        return lookup.fromWire(canonicalEnumToken(text)).orElse(unknown);
    }

    /** confidence: an out-of-set value is an error — we will not choose a calibration on the model's behalf. */
    private static Confidence requireConfidence(JsonNode root) {
        String text = requireText(root, "confidence");
        return Confidence.fromWire(canonicalEnumToken(text))
                .orElseThrow(() -> malformed("field 'confidence' has an unexpected value"));
    }

    private static String canonicalEnumToken(String s) {
        return s.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s\\-]+", "_");
    }

    private static List<String> requireStringArray(JsonNode root, String field, int maxItems, int maxLength) {
        JsonNode n = root.get(field);
        if (n == null || !n.isArray()) {
            throw malformed("field '" + field + "' missing or not an array");
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : n) {
            if (!item.isTextual()) continue; // a non-string element carries nothing usable; drop it
            String value = ActionSafetyPolicy.collapseWhitespace(item.textValue());
            if (value.isEmpty()) continue;
            value = ActionSafetyPolicy.truncate(value, maxLength);
            boolean duplicate = false;
            for (String existing : out) {
                if (existing.equalsIgnoreCase(value)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) out.add(value);
            if (out.size() == maxItems) break;
        }
        return out;
    }

    /** Collapses "no answer" spellings to "unknown"; otherwise trims, caps length and capitalizes the first letter. */
    static String normalizeName(String raw, int maxLength) {
        String value = ActionSafetyPolicy.collapseWhitespace(raw);
        if (value.isEmpty() || UNKNOWN_ALIASES.contains(value.toLowerCase(Locale.ROOT))) {
            return DiagnosisResult.UNKNOWN;
        }
        value = ActionSafetyPolicy.truncate(value, maxLength);
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }

    private static FolixAnalysisException malformed(String internalDetail) {
        return FolixAnalysisException.failed("malformed model response: " + internalDetail, true);
    }
}
