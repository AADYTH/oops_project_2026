package com.cropscanner.folix;

/**
 * The basic counts a future Dashboard will need. Deliberately just counts —
 * no breakdown by crop/disease/date, no aggregation logic. Building that out
 * belongs to the Dashboard stage, not this persistence foundation.
 */
public record FolixReportStats(
        long totalReports,
        long reportsNeedingHumanReview
) {
}
