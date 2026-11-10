package com.cropscanner.model;

import java.util.List;

/**
 * Result of disease classification for one scan, as produced by the
 * inference backend (see {@link com.cropscanner.service.InferenceService}).
 *
 * @param diseaseName        canonical disease/pathogen name, e.g. "Late Blight (Phytophthora infestans)"
 * @param cropName           crop the sample was identified as belonging to, e.g. "Tomato"
 * @param affectedPlantPart  which part of the plant the image showed (leaf, stem, fruit, root)
 * @param confidencePercent  model confidence in the top prediction, 0-100
 * @param severityPercent    estimated percentage of tissue affected/necrotic, 0-100
 * @param isEpidemicRisk     true if this pathogen is on the fast-spreading / quarantine-notifiable watchlist
 * @param remedyPlan         organic and chemical treatment guidance for this disease
 * @param partSupported      legacy field name, now effectively always true: photos that don't contain
 *                           a real leaf are now rejected before classification ever runs (see
 *                           inference-service/leaf_validator.py and NoLeafDetectedException) rather than
 *                           being classified anyway and marked unsupported after the fact. Kept for wire
 *                           compatibility with older frontend builds.
 * @param partWarning        legacy field, effectively always null now — see partSupported above
 * @param modelPanel         every model in the inference-service's ensemble panel and its independent
 *                           verdict, for full transparency about how the final answer was reached.
 *                           Always has at least one entry (a single-model deployment reports its own
 *                           model as a one-member panel) — never null or empty.
 * @param consensus          how the panel reconciled its votes into the result above; never null —
 *                           callers talking to a pre-ensemble inference-service build get a synthesized
 *                           SINGLE_MODEL consensus instead (see RemoteMlInferenceService.parseResponse)
 * @param geminiOpinion      Gemini's independent second opinion on this photo, or null if Gemini was
 *                           never consulted for this scan (disabled, offline, etc. — see
 *                           {@code EvidenceConfidenceService} and {@code CropScannerService}, the only
 *                           places allowed to populate/interpret this). Never influences cropName/
 *                           diseaseName/remedyPlan above — only confidenceLevel, and only via that service.
 */
public record DiseaseInfo(
        String diseaseName,
        String cropName,
        PlantPart affectedPlantPart,
        double confidencePercent,
        double severityPercent,
        boolean isEpidemicRisk,
        RemedyPlan remedyPlan,
        ConfidenceLevel confidenceLevel,
        List<AlternativePrediction> alternatives,
        boolean partSupported,
        String partWarning,
        List<ModelVote> modelPanel,
        Consensus consensus,
        GeminiOpinion geminiOpinion
) {
    public DiseaseInfo {
        if (confidencePercent < 0 || confidencePercent > 100) {
            throw new IllegalArgumentException("confidencePercent must be within [0,100]");
        }
        if (severityPercent < 0 || severityPercent > 100) {
            throw new IllegalArgumentException("severityPercent must be within [0,100]");
        }
        alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        modelPanel = modelPanel == null ? List.of() : List.copyOf(modelPanel);
        if (confidenceLevel == null) {
            confidenceLevel = ConfidenceLevel.fromPercent(confidencePercent);
        }
    }

    /**
     * Compatibility constructor for callers built before {@code geminiOpinion}
     * existed (e.g. {@code RemoteMlInferenceService.parseResponse}, which talks
     * to the Python inference-service — a system that has no concept of Gemini
     * at all, since the Java backend is what owns the Gemini integration).
     * Defaults geminiOpinion to null, meaning "not consulted yet"; see
     * {@link #withConfidenceAndGeminiOpinion(ConfidenceLevel, GeminiOpinion)} for attaching one afterwards.
     */
    public DiseaseInfo(String diseaseName, String cropName, PlantPart affectedPlantPart,
                        double confidencePercent, double severityPercent, boolean isEpidemicRisk,
                        RemedyPlan remedyPlan, ConfidenceLevel confidenceLevel,
                        List<AlternativePrediction> alternatives, boolean partSupported, String partWarning,
                        List<ModelVote> modelPanel, Consensus consensus) {
        this(diseaseName, cropName, affectedPlantPart, confidencePercent, severityPercent, isEpidemicRisk,
                remedyPlan, confidenceLevel, alternatives, partSupported, partWarning, modelPanel, consensus, null);
    }

    /**
     * Convenience constructor for callers (tests, older code paths) that
     * don't yet have alternative predictions, part-support info, or panel
     * data to supply. Assumes the part was supported (the common case: a
     * leaf photo) and reports a single-member panel so callers that only
     * care about the top-line result don't need to change.
     */
    public DiseaseInfo(String diseaseName, String cropName, PlantPart affectedPlantPart,
                        double confidencePercent, double severityPercent, boolean isEpidemicRisk,
                        RemedyPlan remedyPlan) {
        this(diseaseName, cropName, affectedPlantPart, confidencePercent, severityPercent,
                isEpidemicRisk, remedyPlan, ConfidenceLevel.fromPercent(confidencePercent), List.of(),
                true, null,
                List.of(new ModelVote(diseaseName, cropName, "single-model", "unknown",
                        confidencePercent, isEpidemicRisk)),
                new Consensus("SINGLE_MODEL", 1, 1, List.of("single-model")), null);
    }

    /** Returns a copy of this result with a different confidence level and/or Gemini opinion attached. */
    public DiseaseInfo withConfidenceAndGeminiOpinion(ConfidenceLevel newConfidenceLevel, GeminiOpinion newGeminiOpinion) {
        return new DiseaseInfo(diseaseName, cropName, affectedPlantPart, confidencePercent, severityPercent,
                isEpidemicRisk, remedyPlan, newConfidenceLevel, alternatives, partSupported, partWarning,
                modelPanel, consensus, newGeminiOpinion);
    }

    /** One panel member's independent verdict on this photo. */
    public record ModelVote(
            String diseaseName,
            String cropName,
            String displayName,
            String architecture,
            double confidencePercent,
            boolean isEpidemicRisk
    ) {
    }

    /**
     * How the panel's votes were reconciled into the final {@code diseaseName}/
     * {@code cropName}/{@code confidencePercent} above.
     *
     * @param level               UNANIMOUS (every model agreed), MAJORITY (more than half agreed,
     *                            confidence is capped below HIGH regardless of raw score — see
     *                            inference-service/ensemble.py), SPLIT (no majority; confidence is
     *                            forced LOW), or SINGLE_MODEL (ensemble disabled / legacy build)
     * @param agreeingModels      how many panel members voted for the winning (crop, disease) pair
     * @param totalModels         how many panel members produced a usable vote at all (a model that
     *                            failed to load is simply absent from both this and modelPanel — see
     *                            inference-service ensemble.py's failure-isolation design)
     * @param agreeingModelNames  display names of the models that agreed on the winning answer
     */
    public record Consensus(
            String level,
            int agreeingModels,
            int totalModels,
            List<String> agreeingModelNames
    ) {
        public Consensus {
            agreeingModelNames = agreeingModelNames == null ? List.of() : List.copyOf(agreeingModelNames);
        }
    }

    /** Which physical part of the plant the submitted photo depicted. */
    public enum PlantPart {
        LEAF, STEM, FRUIT, ROOT, FLOWER, WHOLE_PLANT, UNKNOWN
    }

    /**
     * Confidence gating (Tier 1 upgrade #1): a 40%-confidence guess should
     * never be presented to the user looking identical to a 95%-confidence
     * one. The frontend uses this band, not the raw percentage, to decide
     * whether to show a single confident result or an "uncertain — here
     * are a few possibilities" view with {@link #alternatives()}.
     *
     * Default thresholds mirror the inference service's; override both
     * sides consistently via {@code cropscanner.confidence.*} if you
     * recalibrate them against real accuracy data collected through the
     * feedback loop (see {@code ScanFeedbackEntity}).
     */
    public enum ConfidenceLevel {
        HIGH, MEDIUM, LOW;

        public static ConfidenceLevel fromPercent(double confidencePercent) {
            if (confidencePercent >= 75.0) return HIGH;
            if (confidencePercent >= 45.0) return MEDIUM;
            return LOW;
        }
    }

    /** One runner-up prediction the model considered, for the "uncertain" view. */
    public record AlternativePrediction(String cropName, String diseaseName, double confidencePercent) {
    }

    /**
     * Sealed hierarchy so that every remedy in the system is either a real,
     * named organic practice or a real, named chemical control — never an
     * unstructured free-text "TBD" placeholder.
     */
    public sealed interface Remedy permits OrganicRemedy, ChemicalRemedy {
        String name();
        String instructions();
    }

    public record OrganicRemedy(String name, String instructions, String applicationFrequency) implements Remedy {
    }

    public record ChemicalRemedy(
            String name,
            String activeIngredient,
            String instructions,
            String dosage,
            String preHarvestIntervalDays,
            String safetyPrecautions
    ) implements Remedy {
    }

    /**
     * Bundled treatment guidance for a disease: at least one organic option
     * and, where applicable, chemical options with dosage and safety-interval
     * data drawn from standard agricultural-extension label guidance.
     */
    public record RemedyPlan(List<OrganicRemedy> organicRemedies, List<ChemicalRemedy> chemicalRemedies) {
        public RemedyPlan {
            organicRemedies = List.copyOf(organicRemedies);
            chemicalRemedies = List.copyOf(chemicalRemedies);
            if (organicRemedies.isEmpty() && chemicalRemedies.isEmpty()) {
                throw new IllegalArgumentException("RemedyPlan must contain at least one remedy");
            }
        }
    }
}
