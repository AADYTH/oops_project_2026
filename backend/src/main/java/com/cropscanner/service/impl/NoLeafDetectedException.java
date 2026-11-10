package com.cropscanner.service.impl;

/**
 * Thrown when the inference service's real pixel-evidence leaf validator
 * (see inference-service/leaf_validator.py) determines the submitted photo
 * doesn't contain a usable leaf. Deliberately distinct from
 * {@link RemoteMlInferenceService.ImageQualityException}: this is a content
 * rejection ("there's no leaf here"), not a technical-quality rejection
 * ("there's a leaf here but it's too blurry/dark to read") — the two need
 * different user-facing corrective instructions and neither should be
 * mistaken for the other in logs or the UI.
 *
 * Per README "Leaf-only scanner": no disease is ever fabricated when this
 * fires. Callers must surface NO_LEAF_DETECTED as-is, never fall back to a
 * best-effort guess.
 */
public class NoLeafDetectedException extends RuntimeException {

    private final double plantMaterialRatio;
    private final double largestRegionRatio;

    public NoLeafDetectedException(String message, double plantMaterialRatio, double largestRegionRatio) {
        super(message);
        this.plantMaterialRatio = plantMaterialRatio;
        this.largestRegionRatio = largestRegionRatio;
    }

    public double plantMaterialRatio() {
        return plantMaterialRatio;
    }

    public double largestRegionRatio() {
        return largestRegionRatio;
    }
}
