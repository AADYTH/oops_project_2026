package com.cropscanner.folix.map;

/**
 * Stage 6C — the tunable, privacy-relevant knobs of the Disease Map (bound from {@code folix.map.*} in
 * {@code application.yml}, see {@link FolixMapConfig}).
 *
 * The compact constructor CLAMPS every value to a safe range instead of trusting configuration, so a typo
 * (or a well-meaning "let's show more") can never silently turn the map into a precise-location or
 * single-farmer display:
 * <ul>
 *   <li>{@code cellSizeMillidegrees} — side of one square region, in thousandths of a degree
 *       (100 = 0.1° ≈ 11 km). Never smaller than {@value #MIN_CELL_MILLIDEGREES} (≈ 5.5 km).</li>
 *   <li>{@code minCases} — a (region, crop, disease) group is shown only with at least this many reports
 *       (default 3, the {@code MIN_CASES_FOR_MAP} of the spec). Never less than {@value #MIN_FLOOR}.</li>
 *   <li>{@code minFarmers} — ...AND reports from at least this many DIFFERENT farmers (default 3), so one
 *       farmer submitting several reports can never make a region appear by themselves. Never less than
 *       {@value #MIN_FLOOR}.</li>
 *   <li>{@code lookbackDays} — the time basis: only reports newer than this are aggregated, so old reports
 *       cannot dominate the map (default 30, at most {@value #MAX_LOOKBACK_DAYS}).</li>
 * </ul>
 */
public record FolixMapSettings(int cellSizeMillidegrees, int minCases, int minFarmers, int lookbackDays) {

    public static final int MIN_CELL_MILLIDEGREES = 50;
    public static final int MAX_CELL_MILLIDEGREES = 1000;
    public static final int MIN_FLOOR = 1;
    public static final int MAX_LOOKBACK_DAYS = 365;

    public FolixMapSettings {
        cellSizeMillidegrees = Math.max(MIN_CELL_MILLIDEGREES, Math.min(MAX_CELL_MILLIDEGREES, cellSizeMillidegrees));
        minCases = Math.max(MIN_FLOOR, minCases);
        minFarmers = Math.max(MIN_FLOOR, minFarmers);
        lookbackDays = Math.max(1, Math.min(MAX_LOOKBACK_DAYS, lookbackDays));
    }

    /** The production defaults: ~11 km regions, 1 report, last 30 days. */
    public static FolixMapSettings defaults() {
        return new FolixMapSettings(100, 1, 1, 30);
    }

    /** Side of one region in degrees, for display only. */
    public double cellSizeDegrees() {
        return cellSizeMillidegrees / 1000.0;
    }
}
