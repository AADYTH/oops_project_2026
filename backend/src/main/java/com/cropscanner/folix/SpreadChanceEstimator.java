package com.cropscanner.folix;

import java.util.Locale;

/**
 * Turns two things FOLIX already knows into one farmer-readable number: how likely this disease is to
 * spread to neighbouring plants/fields.
 *
 * <ul>
 *   <li><b>Disease type</b> — the audited {@code epidemic-risk-diseases.json} flag (never the model's
 *       own opinion). A fast-spreading / quarantine-notifiable disease starts from a high base.</li>
 *   <li><b>Visible severity</b> — the higher the share of tissue already affected, the more inoculum
 *       is present, so the chance rises with it.</li>
 * </ul>
 *
 * This is a transparent heuristic, not a calibrated epidemiological forecast, and the UI labels it an
 * "estimate". It is deliberately INDEPENDENT of the model's confidence: a low-confidence guess at a
 * named disease still has a severity and a spread risk worth showing (the result is separately
 * flagged low-confidence). It returns {@code null} — never a made-up number — when there is no
 * disease to reason about (no plant, unknown disease, or a healthy plant).
 */
public final class SpreadChanceEstimator {

    static final int BASE_EPIDEMIC = 55;
    static final int BASE_ORDINARY = 15;
    static final double SEVERITY_WEIGHT = 0.40;
    static final int MIN = 5;
    static final int MAX = 95;

    private SpreadChanceEstimator() {
    }

    /** @return 5..95, or null when no honest estimate exists. */
    public static Integer estimate(DiagnosisResult r) {
        if (r == null || !r.plantDetected() || r.diseaseIsUnknown() || isHealthy(r.disease())) {
            return null;
        }
        Integer sev = effectiveSeverityPercent(r);
        int base = r.epidemicRisk() ? BASE_EPIDEMIC : BASE_ORDINARY;
        int value = base + (sev == null ? 0 : (int) Math.round(sev * SEVERITY_WEIGHT));
        return Math.max(MIN, Math.min(MAX, value));
    }

    /** The model's own percentage if it gave one, otherwise the midpoint of its severity band. */
    static Integer effectiveSeverityPercent(DiagnosisResult r) {
        if (r.severityPercent() != null) {
            return r.severityPercent();
        }
        return switch (r.severity()) {
            case MILD -> 15;
            case MODERATE -> 45;
            case SEVERE -> 75;
            case UNKNOWN -> null;
        };
    }

    static boolean isHealthy(String disease) {
        return disease != null && disease.trim().toLowerCase(Locale.ROOT).startsWith("healthy");
    }
}
