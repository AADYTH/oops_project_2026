package com.cropscanner.folix.map;

import com.cropscanner.folix.location.FolixRegionCell;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The privacy rules of the Disease Map, as a pure function (no database, no framework) so they can be tested
 * exhaustively.
 *
 * <ol>
 *   <li><b>Relevance.</b> Only reports that describe a diagnosed disease count: "Healthy" / "unknown" diagnoses
 *       and LOW-confidence diagnoses are left out (they are not reliable evidence of a regional trend).</li>
 *   <li><b>Grouping.</b> By (coarse region, crop, disease). A region is an exact integer grid cell.</li>
 *   <li><b>Minimum-case protection.</b> A group is shown only if it has at least {@code minCases} reports AND
 *       reports from at least {@code minFarmers} DIFFERENT farmers. So one farmer's report — or several reports
 *       from the same farmer — can never appear on the map by itself. Groups below the threshold are dropped, not
 *       shown with a small count, and are not counted in any region total either (so totals cannot be used to
 *       infer a hidden group).</li>
 *   <li><b>No identity out.</b> Farmer ids are used only for the distinct count and are not copied to the output.</li>
 * </ol>
 */
public final class FolixDiseaseMapAggregator {

    private FolixDiseaseMapAggregator() { }

    public static FolixDiseaseMapResponse aggregate(List<FolixMapRow> rows, FolixMapSettings settings) {
        // region -> (crop|disease key) -> accumulated group
        Map<FolixRegionCell, Map<String, Group>> byRegion = new LinkedHashMap<>();
        for (FolixMapRow row : rows) {
            if (!isRelevant(row)) {
                continue;
            }
            FolixRegionCell cell = new FolixRegionCell(row.regionLatMdeg(), row.regionLngMdeg());
            String key = normalize(row.crop()) + "\u0000" + normalize(row.disease());
            byRegion.computeIfAbsent(cell, c -> new LinkedHashMap<>())
                    .computeIfAbsent(key, k -> new Group())
                    .add(row);
        }

        List<FolixDiseaseMapResponse.Region> regions = new ArrayList<>();
        for (Map.Entry<FolixRegionCell, Map<String, Group>> region : byRegion.entrySet()) {
            List<FolixDiseaseMapResponse.Entry> entries = new ArrayList<>();
            for (Group g : region.getValue().values()) {
                if (g.reports >= settings.minCases() && g.farmers.size() >= settings.minFarmers()) {
                    entries.add(g.toEntry());
                }
            }
            if (entries.isEmpty()) {
                continue; // nothing in this region met the threshold: the region is not shown at all
            }
            entries.sort(Comparator.comparingInt(FolixDiseaseMapResponse.Entry::reportCount).reversed()
                    .thenComparing(FolixDiseaseMapResponse.Entry::crop)
                    .thenComparing(FolixDiseaseMapResponse.Entry::disease));
            int total = entries.stream().mapToInt(FolixDiseaseMapResponse.Entry::reportCount).sum();
            regions.add(new FolixDiseaseMapResponse.Region(
                    region.getKey().centerLatitude(), region.getKey().centerLongitude(), total, List.copyOf(entries)));
        }
        regions.sort(Comparator.comparingInt(FolixDiseaseMapResponse.Region::totalReports).reversed()
                .thenComparingDouble(FolixDiseaseMapResponse.Region::centerLatitude)
                .thenComparingDouble(FolixDiseaseMapResponse.Region::centerLongitude));

        return new FolixDiseaseMapResponse(settings.lookbackDays(), settings.minCases(),
                settings.cellSizeDegrees(), List.copyOf(regions));
    }

    /** A report is relevant if it has a region, names a real disease, is not low confidence, and has severity above 30. */
    static boolean isRelevant(FolixMapRow row) {
        if (row == null || row.regionLatMdeg() == null || row.regionLngMdeg() == null || row.farmerId() == null) {
            return false;
        }
        String crop = normalize(row.crop());
        String disease = normalize(row.disease());
        if (crop.isEmpty() || disease.isEmpty() || disease.equals("healthy") || disease.equals("unknown")) {
            return false;
        }
        if ("low".equals(normalize(row.confidence()))) {
            return false;
        }
        if (row.severityPercent() != null) {
            return row.severityPercent() >= 30;
        }
        return true;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /** Reports of one (region, crop, disease). */
    private static final class Group {
        private int reports;
        private final Set<UUID> farmers = new HashSet<>();
        private final TreeMap<String, Integer> cropSpellings = new TreeMap<>();
        private final TreeMap<String, Integer> diseaseSpellings = new TreeMap<>();
        private int mild, moderate, severe, unknown;

        void add(FolixMapRow row) {
            reports++;
            farmers.add(row.farmerId());
            cropSpellings.merge(row.crop().trim(), 1, Integer::sum);
            diseaseSpellings.merge(row.disease().trim(), 1, Integer::sum);
            switch (normalize(row.severity())) {
                case "mild" -> mild++;
                case "moderate" -> moderate++;
                case "severe" -> severe++;
                default -> unknown++;
            }
        }

        FolixDiseaseMapResponse.Entry toEntry() {
            return new FolixDiseaseMapResponse.Entry(mostCommon(cropSpellings), mostCommon(diseaseSpellings), reports,
                    new FolixDiseaseMapResponse.Severity(mild, moderate, severe, unknown));
        }

        /** The most frequent original spelling; ties resolve alphabetically (TreeMap order) so output is deterministic. */
        private static String mostCommon(TreeMap<String, Integer> spellings) {
            String best = null;
            int bestCount = -1;
            for (Map.Entry<String, Integer> e : spellings.entrySet()) {
                if (e.getValue() > bestCount) {
                    best = e.getKey();
                    bestCount = e.getValue();
                }
            }
            return best;
        }
    }
}
