package com.cropscanner.folix;

import com.cropscanner.folix.auth.FolixFarmerEntity;
import com.cropscanner.folix.auth.FolixFarmerRepository;
import com.cropscanner.folix.auth.FolixFarmerStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises {@link FolixScanReportRepository} against a real (embedded H2)
 * database, so the entity mapping, the JSON list converter, and the derived
 * queries are checked against actual persistence rather than a mock.
 *
 * Stage 6B: every report has an owning farmer, so each test first persists two real
 * {@link FolixFarmerEntity} rows and the scoped queries are checked against real persistence —
 * including that one farmer can never see, count or fetch another's reports.
 *
 * NOTE: this test was written but could not be executed in this environment
 * — see the Stage 5A/6B summaries for details.
 */
@DataJpaTest
class FolixScanReportRepositoryTest {

    @Autowired
    private FolixScanReportRepository repository;

    @Autowired
    private FolixFarmerRepository farmerRepository;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

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

    private FolixScanReportEntity reportFor(FolixFarmerEntity owner, String crop, String disease, Instant createdAt,
                                            boolean needsReview, FolixReportStatus status) {
        return new FolixScanReportEntity(
                UUID.randomUUID(), owner, createdAt, crop, "leaf", disease, "moderate", "high",
                List.of("spot a", "spot b"), "some reasoning", List.of("do this", "do that"),
                needsReview, status);
    }

    /** Stage 5 tests below are unchanged in intent; their reports simply belong to farmer A now. */
    private FolixScanReportEntity report(String crop, String disease, Instant createdAt,
                                         boolean needsReview, FolixReportStatus status) {
        return reportFor(farmerA, crop, disease, createdAt, needsReview, status);
    }

    @Test
    void savesAndReloadsAllFieldsIncludingListColumns() {
        FolixScanReportEntity saved = repository.save(
                report("Tomato", "Early blight", Instant.now(), false, FolixReportStatus.RECEIVED));

        Optional<FolixScanReportEntity> found = repository.findById(saved.getId());

        assertTrue(found.isPresent());
        FolixScanReportEntity e = found.get();
        assertEquals("Tomato", e.getCrop());
        assertEquals("Early blight", e.getDisease());
        assertEquals("leaf", e.getPlantPart());
        assertEquals(List.of("spot a", "spot b"), e.getSymptoms());
        assertEquals(List.of("do this", "do that"), e.getRecommendedActions());
        assertEquals(FolixReportStatus.RECEIVED, e.getStatus());
        assertFalse(e.isNeedsHumanReview());
    }

    @Test
    void missingReportIsEmptyNotAnException() {
        assertTrue(repository.findById(UUID.randomUUID()).isEmpty());
    }

    @Test
    void findAllByOrderByCreatedAtDescReturnsNewestFirst() {
        Instant now = Instant.now();
        FolixScanReportEntity oldest = repository.save(report("Tomato", "A", now.minus(2, ChronoUnit.DAYS), false, FolixReportStatus.RECEIVED));
        FolixScanReportEntity middle = repository.save(report("Tomato", "B", now.minus(1, ChronoUnit.DAYS), false, FolixReportStatus.RECEIVED));
        FolixScanReportEntity newest = repository.save(report("Tomato", "C", now, false, FolixReportStatus.RECEIVED));

        Page<FolixScanReportEntity> page = repository.findAllByFarmer_IdOrderByCreatedAtDesc(farmerA.getId(), PageRequest.of(0, 10));

        assertEquals(3, page.getTotalElements());
        assertEquals(newest.getId(), page.getContent().get(0).getId());
        assertEquals(middle.getId(), page.getContent().get(1).getId());
        assertEquals(oldest.getId(), page.getContent().get(2).getId());
    }

    @Test
    void paginationSplitsResultsAcrossPages() {
        Instant now = Instant.now();
        for (int i = 0; i < 5; i++) {
            repository.save(report("Tomato", "Disease " + i, now.plusSeconds(i), false, FolixReportStatus.RECEIVED));
        }

        Page<FolixScanReportEntity> firstPage = repository.findAllByFarmer_IdOrderByCreatedAtDesc(farmerA.getId(), PageRequest.of(0, 2));
        Page<FolixScanReportEntity> secondPage = repository.findAllByFarmer_IdOrderByCreatedAtDesc(farmerA.getId(), PageRequest.of(1, 2));

        assertEquals(5, firstPage.getTotalElements());
        assertEquals(3, firstPage.getTotalPages());
        assertEquals(2, firstPage.getContent().size());
        assertEquals(2, secondPage.getContent().size());
        assertNotEquals(firstPage.getContent().get(0).getId(), secondPage.getContent().get(0).getId());
    }

    @Test
    void countByNeedsHumanReviewTrueCountsOnlyFlaggedReports() {
        repository.save(report("Tomato", "A", Instant.now(), true, FolixReportStatus.RECEIVED));
        repository.save(report("Tomato", "B", Instant.now(), true, FolixReportStatus.RECEIVED));
        repository.save(report("Tomato", "C", Instant.now(), false, FolixReportStatus.RECEIVED));

        assertEquals(2, repository.countByFarmer_IdAndNeedsHumanReviewTrue(farmerA.getId()));
    }

    @Test
    void countByStatusCountsOnlyThatStatus() {
        repository.save(report("Tomato", "A", Instant.now(), false, FolixReportStatus.RECEIVED));
        repository.save(report("Tomato", "B", Instant.now(), false, FolixReportStatus.REVIEWED));

        assertEquals(1, repository.countByStatus(FolixReportStatus.RECEIVED));
        assertEquals(1, repository.countByStatus(FolixReportStatus.REVIEWED));
        assertEquals(0, repository.countByStatus(FolixReportStatus.ARCHIVED));
    }

    // ------------------------------------------------------------------
    // Stage 6B: ownership
    // ------------------------------------------------------------------

    @Test
    void theOwnerIsPersistedAndReloaded() {
        FolixScanReportEntity saved = repository.saveAndFlush(reportFor(farmerB, "Tomato", "Early blight",
                Instant.now(), false, FolixReportStatus.RECEIVED));
        entityManager.clear(); // force a real reload from the database, not the persistence-context copy

        FolixScanReportEntity found = repository.findById(saved.getId()).orElseThrow();

        assertNotNull(found.getFarmer());
        assertEquals(farmerB.getId(), found.getFarmer().getId());
    }

    @Test
    void listIsScopedToTheOwningFarmer() {
        Instant now = Instant.now();
        FolixScanReportEntity a1 = repository.save(reportFor(farmerA, "Tomato", "A1", now.minusSeconds(20), false, FolixReportStatus.RECEIVED));
        FolixScanReportEntity a2 = repository.save(reportFor(farmerA, "Tomato", "A2", now.minusSeconds(10), false, FolixReportStatus.RECEIVED));
        FolixScanReportEntity b1 = repository.save(reportFor(farmerB, "Chilli", "B1", now, false, FolixReportStatus.RECEIVED));

        Page<FolixScanReportEntity> forA = repository.findAllByFarmer_IdOrderByCreatedAtDesc(farmerA.getId(), PageRequest.of(0, 10));
        Page<FolixScanReportEntity> forB = repository.findAllByFarmer_IdOrderByCreatedAtDesc(farmerB.getId(), PageRequest.of(0, 10));

        assertEquals(2, forA.getTotalElements());
        assertEquals(List.of(a2.getId(), a1.getId()), forA.getContent().stream().map(FolixScanReportEntity::getId).toList(),
                "farmer A sees only their own reports, newest first — B's newer report is not in the list");
        assertEquals(1, forB.getTotalElements());
        assertEquals(b1.getId(), forB.getContent().get(0).getId());
    }

    @Test
    void aFarmerWithNoReportsGetsAnEmptyPage() {
        repository.save(reportFor(farmerA, "Tomato", "A", Instant.now(), false, FolixReportStatus.RECEIVED));

        Page<FolixScanReportEntity> forB = repository.findAllByFarmer_IdOrderByCreatedAtDesc(farmerB.getId(), PageRequest.of(0, 10));

        assertEquals(0, forB.getTotalElements());
        assertTrue(forB.getContent().isEmpty());
    }

    @Test
    void findByIdAndFarmerReturnsOnlyTheOwnersReport() {
        FolixScanReportEntity bsReport = repository.save(reportFor(farmerB, "Tomato", "B", Instant.now(), false, FolixReportStatus.RECEIVED));

        assertTrue(repository.findByIdAndFarmer_Id(bsReport.getId(), farmerB.getId()).isPresent());
        assertTrue(repository.findByIdAndFarmer_Id(bsReport.getId(), farmerA.getId()).isEmpty(),
                "farmer A must not be able to fetch farmer B's report by id");
        assertTrue(repository.findByIdAndFarmer_Id(UUID.randomUUID(), farmerA.getId()).isEmpty());
    }

    @Test
    void countsAreScopedToTheOwningFarmer() {
        repository.save(reportFor(farmerA, "Tomato", "A1", Instant.now(), true, FolixReportStatus.RECEIVED));
        repository.save(reportFor(farmerA, "Tomato", "A2", Instant.now(), false, FolixReportStatus.RECEIVED));
        repository.save(reportFor(farmerB, "Chilli", "B1", Instant.now(), true, FolixReportStatus.RECEIVED));
        repository.save(reportFor(farmerB, "Chilli", "B2", Instant.now(), true, FolixReportStatus.RECEIVED));
        repository.save(reportFor(farmerB, "Chilli", "B3", Instant.now(), true, FolixReportStatus.RECEIVED));

        assertEquals(2, repository.countByFarmer_Id(farmerA.getId()));
        assertEquals(1, repository.countByFarmer_IdAndNeedsHumanReviewTrue(farmerA.getId()));
        assertEquals(3, repository.countByFarmer_Id(farmerB.getId()));
        assertEquals(3, repository.countByFarmer_IdAndNeedsHumanReviewTrue(farmerB.getId()));
        assertEquals(0, repository.countByFarmer_Id(UUID.randomUUID()));
    }

    @Test
    void paginationStaysScopedToTheOwningFarmer() {
        Instant now = Instant.now();
        for (int i = 0; i < 5; i++) {
            repository.save(reportFor(farmerA, "Tomato", "A" + i, now.plusSeconds(i), false, FolixReportStatus.RECEIVED));
        }
        for (int i = 0; i < 3; i++) {
            repository.save(reportFor(farmerB, "Chilli", "B" + i, now.plusSeconds(100 + i), false, FolixReportStatus.RECEIVED));
        }

        Page<FolixScanReportEntity> a0 = repository.findAllByFarmer_IdOrderByCreatedAtDesc(farmerA.getId(), PageRequest.of(0, 2));
        Page<FolixScanReportEntity> a1 = repository.findAllByFarmer_IdOrderByCreatedAtDesc(farmerA.getId(), PageRequest.of(1, 2));
        Page<FolixScanReportEntity> a2 = repository.findAllByFarmer_IdOrderByCreatedAtDesc(farmerA.getId(), PageRequest.of(2, 2));
        Page<FolixScanReportEntity> b0 = repository.findAllByFarmer_IdOrderByCreatedAtDesc(farmerB.getId(), PageRequest.of(0, 2));

        assertEquals(5, a0.getTotalElements());
        assertEquals(3, a0.getTotalPages());
        assertEquals(2, a0.getContent().size());
        assertEquals(2, a1.getContent().size());
        assertEquals(1, a2.getContent().size());
        assertEquals(3, b0.getTotalElements());
        assertEquals(2, b0.getTotalPages());
        assertTrue(java.util.stream.Stream.of(a0, a1, a2)
                        .flatMap(p -> p.getContent().stream())
                        .allMatch(r -> r.getFarmer().getId().equals(farmerA.getId())),
                "no page of farmer A's history ever contains another farmer's report");
    }
}
