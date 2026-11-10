package com.cropscanner.folix.agrinetwork;

/**
 * The kind of agricultural assistance being described (Stage 6D).
 *
 * Used in two places, both harmless on their own:
 * <ul>
 *   <li>on a farmer's agricultural profile — the kind of support they're most interested in, purely
 *       informational, never required;</li>
 *   <li>on a {@link FolixSupportContactEntity} — the kind of assistance that contact/resource provides.</li>
 * </ul>
 * Deliberately not a single fixed "village officer": the directory is meant to represent several kinds
 * of assistance (an individual officer, an office, extension support, and so on).
 */
public enum FolixSupportCategory {
    AGRICULTURAL_OFFICER,
    KRISHI_BHAVAN,
    EXTENSION_SUPPORT,
    CROP_DISEASE_ASSISTANCE,
    GENERAL_SUPPORT
}
