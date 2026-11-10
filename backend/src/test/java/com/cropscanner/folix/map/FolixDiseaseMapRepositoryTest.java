package com.cropscanner.folix.map;

import com.cropscanner.folix.FolixReportStatus;
import com.cropscanner.folix.FolixScanReportEntity;
import com.cropscanner.folix.FolixScanReportRepository;
import com.cropscanner.folix.auth.FolixFarmerEntity;
import com.cropscanner.folix.auth.FolixFarmerRepository;
import com.cropscanner.folix.auth.FolixFarmerStatus;
import com.cropscanner.folix.location.FolixFarmerLocationEntity;
import com.cropscanner.folix.location.FolixFarmerLocationRepository;
import com.cropscanner.folix.location.FolixRegionCell;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 6C against a real (embedded H2) database, so the new columns, the JPQL constructor-expression query, the
 * bulk region-clearing update and the location table mapping are checked against actual persistence.
 *
 * NOTE: like the other {@code @DataJpaTest}s in this project, this must be run with Maven (Spring Boot test
 * dependencies); see the Stage 6C summary for whether it could be executed.
 */
@DataJpaTest
class FolixDiseaseMapRepositoryTest {

    @Autowired private FolixDiseaseMapRepository mapRepository;
    @Autowired private FolixScanReportRepository reportRepository;
    @Autowired private FolixFarmerRepository farmerRepository;
    @Autowired private FolixFarmerLocationRepository locationRepository;
    @Autowired private jakarta.persistence.EntityManager entityManager;

    private FolixFarmerEntity farmerA;
    private FolixFarmerEntity farmerB;

    @BeforeEach
    void createFarmers() {
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        farmerA = farmerRepository.saveAndFlush(new FolixFarmerEntity(UUID.randomUUID(), "Asha Nair",
                "asha@example.com", "hash-a", FolixFarmerStatus.ACTIVE, now));
        farmerB = farmerRepository.saveAndFlush(new FolixFarmerEntity(UUID.randomUUID(), "Bala Menon",
                "bala@example.com", "hash-b", FolixFarmerStatus.ACTIVE, now));
    }

    private FolixScanReportEntity report(FolixFarmerEntity owner, Instant createdAt, FolixRegionCell regionOrNull) {
        FolixScanReportEntity e = new FolixScanReportEntity(UUID.randomUUID(), owner, createdAt, "Tomato", "leaf",
                "Early blight", "moderate", "high", List.of("spots"), "reasoning", List.of("act"), false,
                FolixReportStatus.RECEIVED);
        if (regionOrNull != null) {
            e.assignRegion(regionOrNull);
        }
        return reportRepository.save(e);
    }

    @Test
    void aReportWithoutARegionRoundTripsWithNullRegionAndIsNeverSelectedForTheMap() {
        FolixScanReportEntity saved = report(farmerA, Instant.parse("2026-09-20T00:00:00Z"), null);
        reportRepository.flush();
        entityManager.clear();

        FolixScanReportEntity reloaded = reportRepository.findByIdAndFarmer_Id(saved.getId(), farmerA.getId()).orElseThrow();
        assertNull(reloaded.getRegionLatMdeg());
        assertNull(reloaded.getRegionLngMdeg());
        assertFalse(reloaded.hasRegion());
        assertTrue(mapRepository.findLocatedRowsSince(Instant.parse("2026-01-01T00:00:00Z")).isEmpty());
    }

    @Test
    void locatedReportsInsideTheWindowAreSelectedAsNarrowRowsOnly() {
        Instant recent = Instant.parse("2026-09-20T00:00:00Z");
        report(farmerA, recent, new FolixRegionCell(11250, 75750));
        report(farmerB, recent, new FolixRegionCell(11250, 75750));
        report(farmerA, Instant.parse("2026-06-01T00:00:00Z"), new FolixRegionCell(11250, 75750));   // too old
        report(farmerA, recent, null);                                                             // no location
        reportRepository.flush();
        entityManager.clear();

        List<FolixMapRow> rows = mapRepository.findLocatedRowsSince(Instant.parse("2026-08-22T00:00:00Z"));

        assertEquals(2, rows.size());
        for (FolixMapRow row : rows) {
            assertEquals(11250, row.regionLatMdeg());
            assertEquals(75750, row.regionLngMdeg());
            assertEquals("Tomato", row.crop());
            assertEquals("Early blight", row.disease());
            assertEquals("moderate", row.severity());
            assertEquals("high", row.confidence());
        }
        assertEquals(2, rows.stream().map(FolixMapRow::farmerId).distinct().count());
    }

    @Test
    void theRealQueryFeedsTheAggregatorEndToEnd() {
        FolixFarmerEntity farmerC = farmerRepository.saveAndFlush(new FolixFarmerEntity(UUID.randomUUID(), "Chandra",
                "chandra@example.com", "hash-c", FolixFarmerStatus.ACTIVE, Instant.parse("2026-09-01T00:00:00Z")));
        Instant recent = Instant.parse("2026-09-20T00:00:00Z");
        for (FolixFarmerEntity f : List.of(farmerA, farmerB)) {
            report(f, recent, new FolixRegionCell(11250, 75750));
        }
        reportRepository.flush();
        Instant since = Instant.parse("2026-08-22T00:00:00Z");
        assertTrue(FolixDiseaseMapAggregator.aggregate(mapRepository.findLocatedRowsSince(since),
                new FolixMapSettings(100, 3, 3, 30)).regions().isEmpty(), "two farmers: below the threshold");

        report(farmerC, recent, new FolixRegionCell(11250, 75750));
        reportRepository.flush();
        assertEquals(1, FolixDiseaseMapAggregator.aggregate(mapRepository.findLocatedRowsSince(since),
                new FolixMapSettings(100, 3, 3, 30)).regions().size());
    }

    @Test
    void withdrawingConsentClearsTheRegionOfOnlyThatFarmersReports() {
        Instant recent = Instant.parse("2026-09-20T00:00:00Z");
        FolixScanReportEntity mineA = report(farmerA, recent, new FolixRegionCell(11250, 75750));
        FolixScanReportEntity mineB = report(farmerB, recent, new FolixRegionCell(11250, 75750));
        locationRepository.saveAndFlush(new FolixFarmerLocationEntity(farmerA.getId(), new FolixRegionCell(11250, 75750), recent));

        int cleared = locationRepository.clearRegionFromReportsOf(farmerA.getId());
        locationRepository.deleteById(farmerA.getId());
        locationRepository.flush();
        entityManager.clear();

        assertEquals(1, cleared);
        assertFalse(reportRepository.findByIdAndFarmer_Id(mineA.getId(), farmerA.getId()).orElseThrow().hasRegion(),
                "A's report is kept, but no longer carries a region");
        assertTrue(reportRepository.findByIdAndFarmer_Id(mineB.getId(), farmerB.getId()).orElseThrow().hasRegion(),
                "B's report is untouched");
        assertEquals(Optional.empty(), locationRepository.findById(farmerA.getId()));
    }

    @Test
    void theLocationTableStoresOnlyTheCoarseRegionAndConsentTimes() {
        Instant t = Instant.parse("2026-09-21T10:00:00Z");
        locationRepository.saveAndFlush(new FolixFarmerLocationEntity(farmerA.getId(), new FolixRegionCell(11250, 75750), t));
        entityManager.clear();

        FolixFarmerLocationEntity loaded = locationRepository.findById(farmerA.getId()).orElseThrow();
        assertEquals(11250, loaded.getRegionLatMdeg());
        assertEquals(75750, loaded.getRegionLngMdeg());
        assertEquals(t, loaded.getConsentedAt());
        assertEquals(t, loaded.getUpdatedAt());
    }
}
