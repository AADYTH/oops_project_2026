package com.cropscanner.folix.forwarding;

/**
 * Lifecycle of a manual case forward (Stage 6E). Stored as a string so later stages can add states
 * without a schema change. {@link #NOT_FORWARDED} is never stored — it is only what the read endpoint
 * reports for a report that has no forward record.
 */
public enum FolixForwardStatus {
    NOT_FORWARDED,
    FORWARDED
}
