package com.cropscanner.folix.map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 6C: the Disease Map's privacy rules, exercised on the pure aggregation function — minimum-case threshold,
 * distinct-farmer threshold, exclusions, and that nothing identifying survives into the output.
 */
class FolixDiseaseMapAggregatorTest {

    private static final FolixMapSettings SETTINGS = new FolixMapSettings(100, 3, 3, 30);   // test harness multi-case setting
    private static final int LAT = 11250;
    private static final int LNG = 75750;

    private final UUID farmer1 = UUID.randomUUID();
    private final UUID farmer2 = UUID.randomUUID();
    private final UUID farmer3 = UUID.randomUUID();
    private final UUID farmer4 = UUID.randomUUID();

    private static FolixMapRow row(int lat, int lng, String crop, String disease, String severity, String confidence,
                                   UUID farmer) {
        return new FolixMapRow(lat, lng, crop, disease, severity, confidence, farmer);
    }

    private static FolixMapRow blight(UUID farmer) {
        return row(LAT, LNG, "Tomato", "Early blight", "moderate", "high", farmer);
    }

    private static FolixDiseaseMapResponse aggregate(List<FolixMapRow> rows) {
        return FolixDiseaseMapAggregator.aggregate(rows, SETTINGS);
    }

    @Test
    void aSingleFarmersSingleReportNeverBecomesAMapPoint() {
        assertTrue(aggregate(List.of(blight(farmer1))).regions().isEmpty());
    }

    @Test
    void belowTheMinimumNumberOfReportsTheAreaIsHidden() {
        assertTrue(aggregate(List.of(blight(farmer1), blight(farmer2))).regions().isEmpty());
    }

    @Test
    void whenTheThresholdIsMetTheAreaAppearsWithItsCounts() {
        FolixDiseaseMapResponse map = aggregate(List.of(blight(farmer1), blight(farmer2), blight(farmer3)));

        assertEquals(1, map.regions().size());
        FolixDiseaseMapResponse.Region region = map.regions().get(0);
        assertEquals(11.25, region.centerLatitude());
        assertEquals(75.75, region.centerLongitude());
        assertEquals(3, region.totalReports());
        FolixDiseaseMapResponse.Entry entry = region.diseases().get(0);
        assertEquals("Tomato", entry.crop());
        assertEquals("Early blight", entry.disease());
        assertEquals(3, entry.reportCount());
        assertEquals(3, entry.severity().moderate());
        assertEquals(3, map.minimumReports());
        assertEquals(30, map.lookbackDays());
    }

    @Test
    void manyReportsFromTheSameFarmerDoNotExposeThatFarmersRegion() {
        List<FolixMapRow> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            rows.add(blight(farmer1));
        }
        assertTrue(aggregate(rows).regions().isEmpty(), "10 reports, 1 farmer: must stay hidden");

        rows.add(blight(farmer2));
        assertTrue(aggregate(rows).regions().isEmpty(), "11 reports, 2 farmers: still below the distinct-farmer floor");

        rows.add(blight(farmer3));
        assertEquals(1, aggregate(rows).regions().size(), "3 distinct farmers: now allowed");
    }

    @Test
    void severityIsSummarisedAsADistribution() {
        FolixDiseaseMapResponse.Severity sev = aggregate(List.of(
                row(LAT, LNG, "Tomato", "Early blight", "mild", "high", farmer1),
                row(LAT, LNG, "Tomato", "Early blight", "severe", "high", farmer2),
                row(LAT, LNG, "Tomato", "Early blight", "severe", "moderate", farmer3),
                row(LAT, LNG, "Tomato", "Early blight", "unknown", "high", farmer4)))
                .regions().get(0).diseases().get(0).severity();
        assertEquals(1, sev.mild());
        assertEquals(0, sev.moderate());
        assertEquals(2, sev.severe());
        assertEquals(1, sev.unknown());
    }

    @Test
    void healthyUnknownAndLowConfidenceDiagnosesAreNotDiseaseReports() {
        List<FolixMapRow> rows = new ArrayList<>();
        for (UUID f : List.of(farmer1, farmer2, farmer3)) {
            rows.add(row(LAT, LNG, "Tomato", "Healthy", "unknown", "high", f));
            rows.add(row(LAT, LNG, "Tomato", "unknown", "unknown", "high", f));
            rows.add(row(LAT, LNG, "Tomato", "Late blight", "severe", "low", f));
        }
        assertTrue(aggregate(rows).regions().isEmpty());
    }

    @Test
    void reportsWithoutARegionAreIgnored() {
        List<FolixMapRow> rows = new ArrayList<>();
        for (UUID f : List.of(farmer1, farmer2, farmer3)) {
            rows.add(new FolixMapRow(null, null, "Tomato", "Early blight", "mild", "high", f));
        }
        assertTrue(aggregate(rows).regions().isEmpty(), "reports with location = unavailable never contribute");
    }

    @Test
    void groupsAreIndependentAndAHiddenGroupIsNotCountedInTheRegionTotal() {
        List<FolixMapRow> rows = new ArrayList<>(List.of(blight(farmer1), blight(farmer2), blight(farmer3)));
        rows.add(row(LAT, LNG, "Tomato", "Leaf curl", "mild", "high", farmer1));   // a lone report of another disease
        FolixDiseaseMapResponse.Region region = aggregate(rows).regions().get(0);
        assertEquals(1, region.diseases().size(), "the lone leaf-curl report is hidden");
        assertEquals(3, region.totalReports(), "and does not leak through the total");
    }

    @Test
    void differentRegionsAreAggregatedSeparately() {
        List<FolixMapRow> rows = new ArrayList<>();
        for (UUID f : List.of(farmer1, farmer2, farmer3)) {
            rows.add(row(LAT, LNG, "Tomato", "Early blight", "mild", "high", f));
        }
        // three reports spread over three DIFFERENT regions: none reaches the threshold
        rows.add(row(12350, 76150, "Tomato", "Early blight", "mild", "high", farmer1));
        rows.add(row(13350, 77150, "Tomato", "Early blight", "mild", "high", farmer2));
        FolixDiseaseMapResponse map = aggregate(rows);
        assertEquals(1, map.regions().size());
        assertEquals(11.25, map.regions().get(0).centerLatitude());
    }

    @Test
    void spellingAndCaseVariantsOfTheSameDiseaseAreOneGroup() {
        FolixDiseaseMapResponse map = aggregate(List.of(
                row(LAT, LNG, "Tomato", "Early blight", "mild", "high", farmer1),
                row(LAT, LNG, " tomato ", "early BLIGHT", "mild", "high", farmer2),
                row(LAT, LNG, "Tomato", "Early blight", "mild", "high", farmer3)));
        assertEquals(1, map.regions().get(0).diseases().size());
        assertEquals(3, map.regions().get(0).diseases().get(0).reportCount());
    }

    @Test
    void aConfiguredLowerThresholdIsStillClampedToMinimumFloor() {
        FolixMapSettings tooLax = new FolixMapSettings(100, 0, 0, 30);
        assertEquals(1, tooLax.minCases());
        assertEquals(1, tooLax.minFarmers());
    }

    @Test
    void singleReportWithSeverityOver30AppearsUnderDefaultSettings() {
        FolixMapRow report = new FolixMapRow(LAT, LNG, "Tomato", "Early blight", "moderate", "high", farmer1, 45);
        FolixDiseaseMapResponse response = FolixDiseaseMapAggregator.aggregate(List.of(report), FolixMapSettings.defaults());
        assertEquals(1, response.regions().size(), "under 1-case defaults, single report appears");
        assertEquals(1, response.regions().get(0).totalReports());
    }

    @Test
    void reportWithSeverityUnder30IsExcluded() {
        FolixMapRow mildLow = new FolixMapRow(LAT, LNG, "Tomato", "Early blight", "mild", "high", farmer1, 20);
        FolixDiseaseMapResponse response = FolixDiseaseMapAggregator.aggregate(List.of(mildLow), FolixMapSettings.defaults());
        assertTrue(response.regions().isEmpty(), "reports with severity < 30 are excluded from the map");
    }

    @Test
    void theSerialisedMapContainsNoFarmerIdentityAndNoExactCoordinates() throws Exception {
        String json = new ObjectMapper().writeValueAsString(
                aggregate(List.of(blight(farmer1), blight(farmer2), blight(farmer3))));

        for (UUID farmer : List.of(farmer1, farmer2, farmer3)) {
            assertFalse(json.contains(farmer.toString()), "farmer id leaked");
        }
        for (String forbidden : List.of("farmer", "email", "name", "session", "password", "\"id\"", "latitude\"",
                "longitude\"")) {
            // the only coordinate fields are centerLatitude / centerLongitude (cell centres), checked below
            String probe = json.replace("centerLatitude", "").replace("centerLongitude", "");
            assertFalse(probe.contains(forbidden), "unexpected field in map JSON: " + forbidden + " in " + json);
        }
        assertTrue(json.contains("\"centerLatitude\":11.25"));
        assertTrue(json.contains("\"centerLongitude\":75.75"));
    }
}
