package com.cropscanner.folix;

import com.cropscanner.model.DiseaseInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The validated, normalized FOLIX assessment — the ONLY shape the Scanner
 * frontend ever receives. Instances are produced exclusively by
 * {@link AssessmentParser}, which enforces the invariants documented there
 * (notably: a result can only move TOWARD uncertainty during normalization,
 * never away from it).
 *
 * The wire format is produced by {@link #toContractMap()} rather than by
 * Jackson annotations, so the exact contract keys/values are explicit,
 * unit-testable without a JSON library, and cannot drift with mapper config.
 */
public record DiagnosisResult(
        boolean plantDetected,
        PlantPart plantPart,
        String crop,
        String disease,
        Severity severity,
        Confidence confidence,
        List<String> symptoms,
        String reasoning,
        List<String> recommendedActions,
        boolean needsHumanReview,
        DiseaseInfo.RemedyPlan remedyPlan,
        boolean epidemicRisk,
        Integer severityPercent,
        Integer spreadChancePercent
) {

    public static final String UNKNOWN = "unknown";

    public DiagnosisResult {
        symptoms = List.copyOf(symptoms);
        recommendedActions = List.copyOf(recommendedActions);
    }

    /**
     * Compatibility constructor for callers built before {@code remedyPlan}/{@code epidemicRisk}
     * existed (e.g. {@link AssessmentParser}, {@link PlantNetDiagnosisMapper} and older tests). Neither
     * Gemini's free-text response nor Pl@ntNet's species response is trusted to supply either of these —
     * they are enriched afterwards by {@link FolixScanService} from the curated remedy knowledge base
     * ({@code JsonRemedyService}) and the maintained epidemic-risk lookup table, never invented here.
     * Defaults {@code remedyPlan} to null ("no matching remedy data found yet") and
     * {@code epidemicRisk} to false.
     */
    public DiagnosisResult(boolean plantDetected, PlantPart plantPart, String crop, String disease,
                            Severity severity, Confidence confidence, List<String> symptoms, String reasoning,
                            List<String> recommendedActions, boolean needsHumanReview) {
        this(plantDetected, plantPart, crop, disease, severity, confidence, symptoms, reasoning,
                recommendedActions, needsHumanReview, null, false, null, null);
    }

    /**
     * Compatibility constructor for callers built before the numeric {@code severityPercent} /
     * {@code spreadChancePercent} existed. Both default to null ("not available").
     */
    public DiagnosisResult(boolean plantDetected, PlantPart plantPart, String crop, String disease,
                            Severity severity, Confidence confidence, List<String> symptoms, String reasoning,
                            List<String> recommendedActions, boolean needsHumanReview,
                            DiseaseInfo.RemedyPlan remedyPlan, boolean epidemicRisk) {
        this(plantDetected, plantPart, crop, disease, severity, confidence, symptoms, reasoning,
                recommendedActions, needsHumanReview, remedyPlan, epidemicRisk, null, null);
    }

    /**
     * Returns a copy of this result with the remedy plan and epidemic-risk flag attached, once they have
     * been looked up (see {@link FolixScanService}). {@code remedyPlan} may be null: that means no
     * matching entry was found in the knowledge base, and the frontend must never fabricate one.
     */
    public DiagnosisResult withRemedyAndEpidemicRisk(DiseaseInfo.RemedyPlan remedyPlan, boolean epidemicRisk) {
        return new DiagnosisResult(plantDetected, plantPart, crop, disease, severity, confidence, symptoms,
                reasoning, recommendedActions, needsHumanReview, remedyPlan, epidemicRisk,
                severityPercent, spreadChancePercent);
    }

    /** Copy with the visible-severity percentage (0-100, reported by the vision model) attached. */
    public DiagnosisResult withSeverityPercent(Integer severityPercent) {
        return new DiagnosisResult(plantDetected, plantPart, crop, disease, severity, confidence, symptoms,
                reasoning, recommendedActions, needsHumanReview, remedyPlan, epidemicRisk,
                severityPercent, spreadChancePercent);
    }

    /** Copy with the deterministic spread-chance estimate attached (see {@link SpreadChanceEstimator}). */
    public DiagnosisResult withSpreadChancePercent(Integer spreadChancePercent) {
        return new DiagnosisResult(plantDetected, plantPart, crop, disease, severity, confidence, symptoms,
                reasoning, recommendedActions, needsHumanReview, remedyPlan, epidemicRisk,
                severityPercent, spreadChancePercent);
    }

    public enum PlantPart {
        LEAF("leaf"), STEM("stem"), FRUIT("fruit"), WHOLE_PLANT("whole_plant"), UNKNOWN("unknown");

        private final String wire;

        PlantPart(String wire) { this.wire = wire; }

        public String wire() { return wire; }

        public static Optional<PlantPart> fromWire(String value) {
            for (PlantPart p : values()) {
                if (p.wire.equals(value)) return Optional.of(p);
            }
            return Optional.empty();
        }
    }

    public enum Severity {
        MILD("mild"), MODERATE("moderate"), SEVERE("severe"), UNKNOWN("unknown");

        private final String wire;

        Severity(String wire) { this.wire = wire; }

        public String wire() { return wire; }

        public static Optional<Severity> fromWire(String value) {
            for (Severity s : values()) {
                if (s.wire.equals(value)) return Optional.of(s);
            }
            return Optional.empty();
        }
    }

    public enum Confidence {
        HIGH("high"), MODERATE("moderate"), LOW("low");

        private final String wire;

        Confidence(String wire) { this.wire = wire; }

        public String wire() { return wire; }

        public static Optional<Confidence> fromWire(String value) {
            for (Confidence c : values()) {
                if (c.wire.equals(value)) return Optional.of(c);
            }
            return Optional.empty();
        }
    }

    /** True when the diagnosis names no specific condition. */
    public boolean diseaseIsUnknown() {
        return UNKNOWN.equals(disease.toLowerCase(Locale.ROOT));
    }

    /**
     * The exact response contract, in contract order:
     * plant_detected, plant_part, crop, disease, severity, confidence,
     * symptoms, reasoning, recommended_actions, needs_human_review,
     * remedy_plan, epidemic_risk, severity_percent, spread_chance_percent.
     *
     * The two trailing percentages are integers 0-100, or null when they cannot be produced honestly
     * (no plant, unknown disease, healthy plant). They are shown regardless of confidence: a
     * low-confidence diagnosis still has a visible severity and a disease-type spread risk, and the
     * frontend labels the whole result as low-confidence instead of hiding these numbers.
     */
    public Map<String, Object> toContractMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("plant_detected", plantDetected);
        m.put("plant_part", plantPart.wire());
        m.put("crop", crop);
        m.put("disease", disease);
        m.put("severity", severity.wire());
        m.put("confidence", confidence.wire());
        m.put("symptoms", symptoms);
        m.put("reasoning", reasoning);
        m.put("recommended_actions", recommendedActions);
        m.put("needs_human_review", needsHumanReview);
        m.put("remedy_plan", remedyPlanContractMap(remedyPlan));
        m.put("epidemic_risk", epidemicRisk);
        m.put("severity_percent", severityPercent);
        m.put("spread_chance_percent", spreadChancePercent);
        return m;
    }

    /**
     * {@code remedyPlan} as a plain, snake_case wire map matching the rest of this contract — or null,
     * meaning FOLIX has no matching remedy data for this disease. Never fabricated: a null here is always
     * exactly what {@code JsonRemedyService.findRemedyPlan} returned (empty).
     */
    private static Map<String, Object> remedyPlanContractMap(DiseaseInfo.RemedyPlan plan) {
        if (plan == null) {
            return null;
        }
        List<Map<String, Object>> organic = new ArrayList<>();
        for (DiseaseInfo.OrganicRemedy r : plan.organicRemedies()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("name", r.name());
            o.put("instructions", r.instructions());
            o.put("application_frequency", r.applicationFrequency());
            organic.add(o);
        }
        List<Map<String, Object>> chemical = new ArrayList<>();
        for (DiseaseInfo.ChemicalRemedy r : plan.chemicalRemedies()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("name", r.name());
            c.put("active_ingredient", r.activeIngredient());
            c.put("instructions", r.instructions());
            c.put("dosage", r.dosage());
            c.put("pre_harvest_interval_days", r.preHarvestIntervalDays());
            c.put("safety_precautions", r.safetyPrecautions());
            chemical.add(c);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("organic_remedies", organic);
        m.put("chemical_remedies", chemical);
        return m;
    }
}
