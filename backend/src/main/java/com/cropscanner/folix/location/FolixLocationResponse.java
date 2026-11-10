package com.cropscanner.folix.location;

import java.time.Instant;

/**
 * The caller's own location-sharing status. When sharing is on, {@code approximateArea} is the CENTRE of the
 * coarse region their reports are attributed to (about {@code cellSizeDegrees} across) — never the coordinates
 * the browser reported. When off, everything except {@code sharingEnabled} is null.
 */
public record FolixLocationResponse(boolean sharingEnabled, Area approximateArea, Instant enabledAt) {

    public record Area(double centerLatitude, double centerLongitude, double cellSizeDegrees) { }

    public static FolixLocationResponse disabled() {
        return new FolixLocationResponse(false, null, null);
    }

    public static FolixLocationResponse of(FolixFarmerLocationEntity e, double cellSizeDegrees) {
        FolixRegionCell cell = e.cell();
        return new FolixLocationResponse(true,
                new Area(cell.centerLatitude(), cell.centerLongitude(), cellSizeDegrees), e.getConsentedAt());
    }
}
