package com.cropscanner.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/**
 * Multipart form fields accompanying an uploaded image. GPS coordinates are
 * optional at the transport level (a user may deny location permission) but
 * are required in practice for the escalation step to run — this is
 * enforced in {@code CropScannerService}, not here, so that a scan can still
 * be classified and shown to the user even without location.
 */
public record ScanRequest(
        @DecimalMin(value = "-90.0", message = "latitude must be >= -90") @DecimalMax(value = "90.0", message = "latitude must be <= 90")
        Double latitude,

        @DecimalMin(value = "-180.0", message = "longitude must be >= -180") @DecimalMax(value = "180.0", message = "longitude must be <= 180")
        Double longitude,

        @NotNull(message = "plantPartHint is required, e.g. LEAF, STEM, FRUIT, ROOT, FLOWER, WHOLE_PLANT")
        String plantPartHint
) {
}
