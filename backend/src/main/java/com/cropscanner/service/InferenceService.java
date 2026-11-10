package com.cropscanner.service;

import com.cropscanner.model.DiseaseInfo;

/**
 * Boundary to the machine-learning inference backend.
 *
 * IMPORTANT: this project deliberately does NOT reimplement plant-disease
 * classification inside Java. Image classification at 80-90%+ real-world
 * accuracy requires a convolutional/transformer model trained on a large,
 * labelled plant-disease dataset (e.g. PlantVillage, PlantDoc, or a
 * proprietary field dataset) — that is a data-science deliverable, not
 * something that can be hand-coded. This interface is the seam where a real
 * trained model is plugged in, served by the companion Python
 * `inference-service` (see /inference-service in this project), which loads
 * an actual Hugging Face image-classification model and runs real
 * OpenCV-based lesion segmentation for severity. See the project README,
 * section "Model accuracy & how to reach 80-90%", for how to swap in and
 * validate a model for your target crops before relying on this in
 * production.
 */
public interface InferenceService {

    /**
     * Sends the raw image bytes to the inference backend and returns the
     * classification, confidence, severity and epidemic-risk flag it
     * computed. Severity is a separate concern from classification
     * (lesion/necrosis area segmentation), but both are computed by the same
     * backend call for efficiency — hence a single combined method rather
     * than two round trips.
     *
     * @param imageBytes       raw JPEG/PNG bytes of the submitted photo
     * @param plantPartHint    what part of the plant the user indicated (improves model routing)
     * @throws InferenceUnavailableException if the inference backend cannot be reached or errors out
     */
    DiseaseInfo classifyAndScore(byte[] imageBytes, String plantPartHint);

    /**
     * Same as {@link #classifyAndScore(byte[], String)}, but lets the caller
     * bypass the image-quality pre-check (blur/brightness). Used as an
     * escape hatch when a legitimate photo gets a false-positive rejection
     * — see README "Known false positives" — so a quality-check bug is
     * never a hard dead end for the user; it becomes "are you sure?"
     * instead. Default implementation ignores the flag for any
     * implementation that hasn't opted in.
     */
    default DiseaseInfo classifyAndScore(byte[] imageBytes, String plantPartHint, boolean skipQualityCheck) {
        return classifyAndScore(imageBytes, plantPartHint);
    }

    /**
     * Thrown when the ML backend cannot be reached or returns an error.
     * Deliberately distinct from a validation exception: callers should
     * surface this as "diagnosis temporarily unavailable", never fall back
     * to a fabricated result.
     */
    class InferenceUnavailableException extends RuntimeException {
        public InferenceUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
