package com.cropscanner.folix.location;

/**
 * Turns browser coordinates into a coarse {@link FolixRegionCell}, and nothing else. The exact coordinates
 * are used inside {@link #coarsen} only and are never returned, stored, logged or persisted by this class.
 *
 * Pure and dependency-free on purpose: the privacy-critical arithmetic can be tested (and reasoned about)
 * on its own.
 */
public final class FolixRegionGrid {

    private static final long MAX_LAT_MDEG = 90_000L;
    private static final long MAX_LNG_MDEG = 180_000L;

    private FolixRegionGrid() { }

    /** True when both values are finite and inside the valid latitude/longitude ranges. */
    public static boolean isValid(Double latitude, Double longitude) {
        return latitude != null && longitude != null
                && Double.isFinite(latitude) && Double.isFinite(longitude)
                && latitude >= -90.0 && latitude <= 90.0
                && longitude >= -180.0 && longitude <= 180.0;
    }

    /**
     * The grid cell containing the point.
     *
     * @param cellSizeMillidegrees side of a cell in thousandths of a degree (see {@code FolixMapSettings})
     * @throws IllegalArgumentException for a missing, non-finite or out-of-range coordinate, or a cell size &lt;= 0
     */
    public static FolixRegionCell coarsen(Double latitude, Double longitude, int cellSizeMillidegrees) {
        if (cellSizeMillidegrees <= 0) {
            throw new IllegalArgumentException("cell size must be positive");
        }
        if (!isValid(latitude, longitude)) {
            throw new IllegalArgumentException("latitude/longitude are missing or out of range");
        }
        long latMilli = Math.round(latitude * 1000.0);
        long lngMilli = Math.round(longitude * 1000.0);
        return new FolixRegionCell(
                centre(latMilli, cellSizeMillidegrees, MAX_LAT_MDEG),
                centre(lngMilli, cellSizeMillidegrees, MAX_LNG_MDEG));
    }

    private static int centre(long milli, int cell, long limit) {
        long index = Math.floorDiv(milli, (long) cell);
        long centre = index * cell + cell / 2;
        return (int) Math.max(-limit, Math.min(limit, centre));
    }
}
