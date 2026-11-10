package com.cropscanner.folix;

/**
 * Lifecycle status of a persisted {@link FolixScanReportEntity}.
 *
 * Stage 5A only ever creates reports in {@link #RECEIVED}. Nothing in this
 * stage transitions a report to another status — that belongs to a later
 * Dashboard/review stage — but the field is modeled now so the column and
 * its values exist before that workflow is built.
 */
public enum FolixReportStatus {
    /** Persisted straight from a completed Gemini diagnosis; not yet reviewed by anyone. */
    RECEIVED,
    /** Reserved for a future human-review workflow. Not set by anything in Stage 5A. */
    REVIEWED,
    /** Reserved for a future archival workflow. Not set by anything in Stage 5A. */
    ARCHIVED
}
