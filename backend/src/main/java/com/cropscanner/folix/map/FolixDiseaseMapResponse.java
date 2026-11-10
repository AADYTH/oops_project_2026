package com.cropscanner.folix.map;

import java.util.List;

/**
 * The ONLY shape the Disease Map API returns: aggregated regional information and nothing that identifies a
 * farmer or a position inside a region. No farmer id/name/email, no session data, no report id, no exact
 * latitude/longitude — {@code centerLatitude}/{@code centerLongitude} are the centre of a coarse grid cell
 * (about {@code areaSizeDegrees} across), the same value for every report inside it.
 *
 * @param lookbackDays   the time basis: only reports from the last N days are counted
 * @param minimumReports the minimum number of reports a group needs before it is shown
 * @param areaSizeDegrees side of one region, in degrees (for drawing the area and for the privacy note)
 */
public record FolixDiseaseMapResponse(int lookbackDays, int minimumReports, double areaSizeDegrees,
                                      List<Region> regions) {

    /** One coarse region and the disease groups in it that met the privacy thresholds. */
    public record Region(double centerLatitude, double centerLongitude, int totalReports, List<Entry> diseases) { }

    /** One (crop, disease) group within a region. */
    public record Entry(String crop, String disease, int reportCount, Severity severity) { }

    /** How many of the group's reports were rated at each severity. */
    public record Severity(int mild, int moderate, int severe, int unknown) { }
}
