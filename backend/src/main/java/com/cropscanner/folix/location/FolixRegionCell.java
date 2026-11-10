package com.cropscanner.folix.location;

/**
 * One coarse geographic region: the CENTRE of a square grid cell, in integer millidegrees
 * (thousandths of a degree). This — never the browser's coordinates — is the only location FOLIX keeps.
 * Integers make "same region" an exact comparison (no floating-point grouping surprises).
 *
 * A cell centre is not any farmer's position: every point inside the cell (about 11 km across by default)
 * maps to the same value.
 */
public record FolixRegionCell(int latMdeg, int lngMdeg) {

    public double centerLatitude() { return latMdeg / 1000.0; }

    public double centerLongitude() { return lngMdeg / 1000.0; }
}
