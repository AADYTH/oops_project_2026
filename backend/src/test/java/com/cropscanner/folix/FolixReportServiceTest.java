package com.cropscanner.folix;

import com.cropscanner.folix.location.FolixFarmerLocationRepository;
import com.cropscanner.folix.auth.AuthenticatedFarmer;
import com.cropscanner.folix.auth.FolixAuthException;
import com.cropscanner.folix.auth.FolixFarmerEntity;
import com.cropscanner.folix.auth.FolixFarmerRepository;
import com.cropscanner.folix.auth.FolixFarmerStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FolixReportService}. The repository is mocked so these
 * run without a database; {@link FolixScanReportRepositoryTest} covers the
 * real persistence path.
 */
class FolixReportServiceTest {

    // Two distinct farmers. The service only ever sees the AuthenticatedFarmer resolved from the session.
    private static final AuthenticatedFarmer FARMER_A = principal("Asha Nair", "asha@example.com");
    private static final AuthenticatedFarmer FARMER_B = principal("Bala Menon", "bala@example.com");

    private static AuthenticatedFarmer principal(String name, String email) {
        return new AuthenticatedFarmer(UUID.randomUUID(), name, email, Instant.parse("2026-09-01T00:00:00Z"));
    }

    private static FolixFarmerEntity farmerEntity(AuthenticatedFarmer f) {
        return new FolixFarmerEntity(f.id(), f.name(), f.email(), "hash", FolixFarmerStatus.ACTIVE, f.createdAt());
    }

    /** A farmer repository whose getReferenceById returns the row for FARMER_A / FARMER_B, like the real one would. */
    private static FolixFarmerRepository farmers() {
        FolixFarmerRepository repo = Mockito.mock(FolixFarmerRepository.class);
        when(repo.getReferenceById(FARMER_A.id())).thenReturn(farmerEntity(FARMER_A));
        when(repo.getReferenceById(FARMER_B.id())).thenReturn(farmerEntity(FARMER_B));
        return repo;
    }

    private static FolixReportService service(FolixScanReportRepository repo) {
        return new FolixReportService(repo, farmers(), Mockito.mock(FolixFarmerLocationRepository.class));
    }

    private static FolixReportRequest validRequest() {
        return new FolixReportRequest(
                "Tomato",
                "leaf",
                "Early blight",
                "moderate",
                "high",
                List.of("Brown concentric-ring spots", "Yellow halo around lesions"),
                "Concentric target-like rings are typical of early blight.",
                List.of("Remove affected leaves.", "Ask a local agricultural officer to confirm."),
                false
        );
    }

    private static FolixScanReportEntity savedEntity(FolixReportRequest r) {
        return new FolixScanReportEntity(
                UUID.randomUUID(), farmerEntity(FARMER_A), Instant.now(), r.crop(), r.plantPart(), r.disease(), r.severity(),
                r.confidence(), r.symptoms(), r.reasoning(), r.recommendedActions(), r.needsHumanReview(),
                FolixReportStatus.RECEIVED);
    }

    // ------------------------------------------------------------------
    // valid report creation
    // ------------------------------------------------------------------

    @Test
    void validReportIsSavedAndReturnedAsReceived() {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);
        FolixReportRequest request = validRequest();
        ArgumentCaptor<FolixScanReportEntity> captor = ArgumentCaptor.forClass(FolixScanReportEntity.class);
        when(repo.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        FolixReportResponse response = service(repo).save(request, FARMER_A);

        assertEquals("Tomato", response.crop());
        assertEquals("Early blight", response.disease());
        assertEquals("RECEIVED", response.status());
        assertNotNull(response.id());
        assertNotNull(response.createdAt());
        FolixScanReportEntity saved = captor.getValue();
        assertEquals(FolixReportStatus.RECEIVED, saved.getStatus());
        assertEquals(2, saved.getSymptoms().size());
    }

    @Test
    void savingFromADiagnosisResultUsesItsExactFields() {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        DiagnosisResult diagnosis = new DiagnosisResult(
                true, DiagnosisResult.PlantPart.LEAF, "Tomato", "Early blight",
                DiagnosisResult.Severity.MODERATE, DiagnosisResult.Confidence.HIGH,
                List.of("spot"), "reasoning text", List.of("action"), false);

        FolixReportResponse response = service(repo).save(diagnosis, FARMER_A);

        assertEquals("Tomato", response.crop());
        assertEquals("leaf", response.plantPart());
        assertEquals("moderate", response.severity());
        assertEquals("high", response.confidence());
        assertFalse(response.needsHumanReview());
    }

    @Test
    void severityAndSpreadPercentagesAreStoredClampedAndReturned_evenForALowConfidenceReport() {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);
        ArgumentCaptor<FolixScanReportEntity> captor = ArgumentCaptor.forClass(FolixScanReportEntity.class);
        when(repo.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));
        FolixReportRequest request = new FolixReportRequest(
                "Tomato", "leaf", "Late blight", "moderate", "low",
                List.of("spots"), "reasoning", List.of("action"), true, null, true, 40, 250);

        FolixReportResponse response = service(repo).save(request, FARMER_A);

        assertEquals(Integer.valueOf(40), response.severityPercent());
        assertEquals(Integer.valueOf(100), response.spreadChancePercent(), "out-of-range values are clamped");
        assertEquals(Integer.valueOf(40), captor.getValue().getSeverityPercent());
        assertTrue(response.epidemicRisk());
    }

    @Test
    void reportsWithoutPercentagesKeepThemNullNeverZero() {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FolixReportResponse response = service(repo).save(validRequest(), FARMER_A);

        assertNull(response.severityPercent());
        assertNull(response.spreadChancePercent());
    }

    // ------------------------------------------------------------------
    // invalid report data / malformed requests are rejected
    // ------------------------------------------------------------------

    @Test
    void nullRequestIsRejected() {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);
        FolixReportException e = assertThrows(FolixReportException.class,
                () -> service(repo).save((FolixReportRequest) null, FARMER_A));
        assertEquals(FolixReportException.Code.VALIDATION_FAILED, e.code());
        Mockito.verifyNoInteractions(repo);
    }

    @Test
    void nullDiagnosisIsRejected() {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);
        FolixReportException e = assertThrows(FolixReportException.class,
                () -> service(repo).save((DiagnosisResult) null, FARMER_A));
        assertEquals(FolixReportException.Code.VALIDATION_FAILED, e.code());
        Mockito.verifyNoInteractions(repo);
    }

    @Test
    void blankCropIsRejected() {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);
        FolixReportRequest bad = withCrop(validRequest(), "  ");
        assertThrows(FolixReportException.class, () -> service(repo).save(bad, FARMER_A));
        Mockito.verifyNoInteractions(repo);
    }

    @Test
    void invalidSeverityValueIsRejected() {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);
        FolixReportRequest bad = withSeverity(validRequest(), "catastrophic");
        FolixReportException e = assertThrows(FolixReportException.class,
                () -> service(repo).save(bad, FARMER_A));
        assertEquals(FolixReportException.Code.VALIDATION_FAILED, e.code());
        Mockito.verifyNoInteractions(repo);
    }

    @Test
    void invalidConfidenceValueIsRejected() {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);
        FolixReportRequest bad = withConfidence(validRequest(), "extremely-sure");
        assertThrows(FolixReportException.class, () -> service(repo).save(bad, FARMER_A));
        Mockito.verifyNoInteractions(repo);
    }

    @Test
    void invalidPlantPartValueIsRejected() {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);
        FolixReportRequest bad = withPlantPart(validRequest(), "roots-and-all");
        assertThrows(FolixReportException.class, () -> service(repo).save(bad, FARMER_A));
        Mockito.verifyNoInteractions(repo);
    }

    @Test
    void blankReasoningIsRejected() {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);
        FolixReportRequest bad = withReasoning(validRequest(), "");
        assertThrows(FolixReportException.class, () -> service(repo).save(bad, FARMER_A));
        Mockito.verifyNoInteractions(repo);
    }

    // ------------------------------------------------------------------
    // unknown disease handling
    // ------------------------------------------------------------------

    @Test
    void unknownDiseaseIsPersistedAsUnknownNotInvented() {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        FolixReportRequest unknown = new FolixReportRequest(
                "unknown", "unknown", "unknown", "unknown", "low",
                List.of(), "The photo shows no visible plant.", List.of(), true);

        FolixReportResponse response = service(repo).save(unknown, FARMER_A);

        assertEquals("unknown", response.disease());
        assertEquals("unknown", response.crop());
        assertTrue(response.needsHumanReview());
    }

    // ------------------------------------------------------------------
    // low-confidence / human-review invariant
    // ------------------------------------------------------------------

    @Test
    void lowConfidenceWithoutHumanReviewIsRejected() {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);
        FolixReportRequest bad = new FolixReportRequest(
                "Tomato", "leaf", "Early blight", "mild", "low",
                List.of(), "uncertain reasoning", List.of(), false);

        FolixReportException e = assertThrows(FolixReportException.class,
                () -> service(repo).save(bad, FARMER_A));
        assertEquals(FolixReportException.Code.VALIDATION_FAILED, e.code());
        Mockito.verifyNoInteractions(repo);
    }

    @Test
    void lowConfidenceWithHumanReviewIsAccepted() {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        FolixReportRequest ok = new FolixReportRequest(
                "Tomato", "leaf", "Early blight", "mild", "low",
                List.of(), "uncertain reasoning", List.of(), true);

        FolixReportResponse response = service(repo).save(ok, FARMER_A);

        assertTrue(response.needsHumanReview());
        assertEquals("low", response.confidence());
    }

    // ------------------------------------------------------------------
    // retrieving a report / missing report
    // ------------------------------------------------------------------

    @Test
    void existingReportIsRetrievedById() {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);
        FolixScanReportEntity entity = savedEntity(validRequest());
        when(repo.findByIdAndFarmer_Id(entity.getId(), FARMER_A.id())).thenReturn(Optional.of(entity));

        FolixReportResponse response = service(repo).getById(entity.getId(), FARMER_A);

        assertEquals(entity.getId(), response.id());
        assertEquals(entity.getDisease(), response.disease());
    }

    @Test
    void missingReportRaisesNotFound() {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);
        UUID missing = UUID.randomUUID();
        when(repo.findByIdAndFarmer_Id(missing, FARMER_A.id())).thenReturn(Optional.empty());

        FolixReportException e = assertThrows(FolixReportException.class,
                () -> service(repo).getById(missing, FARMER_A));
        assertEquals(FolixReportException.Code.NOT_FOUND, e.code());
    }

    @Test
    void nullIdIsRejectedBeforeHittingTheRepository() {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);
        assertThrows(FolixReportException.class, () -> service(repo).getById(null, FARMER_A));
        Mockito.verifyNoInteractions(repo);
    }

    // ------------------------------------------------------------------
    // pagination
    // ------------------------------------------------------------------

    @Test
    void listDelegatesToNewestFirstPagingAndClampsSize() {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        Page<FolixScanReportEntity> page = new PageImpl<>(List.of(savedEntity(validRequest())));
        when(repo.findAllByFarmer_IdOrderByCreatedAtDesc(eq(FARMER_A.id()), captor.capture())).thenReturn(page);

        FolixReportPage result = service(repo).list(FARMER_A, 0, 500);

        assertEquals(1, result.reports().size());
        assertEquals(100, captor.getValue().getPageSize(), "size must be clamped to the maximum");
    }

    @Test
    void listDefaultsSizeWhenNonPositive() {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        when(repo.findAllByFarmer_IdOrderByCreatedAtDesc(eq(FARMER_A.id()), captor.capture())).thenReturn(Page.empty());

        service(repo).list(FARMER_A, -1, 0);

        assertEquals(0, captor.getValue().getPageNumber());
        assertEquals(20, captor.getValue().getPageSize());
    }

    // ------------------------------------------------------------------
    // stats
    // ------------------------------------------------------------------

    @Test
    void statsReportsTotalAndNeedingReviewCounts() {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);
        when(repo.countByFarmer_Id(FARMER_A.id())).thenReturn(42L);
        when(repo.countByFarmer_IdAndNeedsHumanReviewTrue(FARMER_A.id())).thenReturn(7L);

        FolixReportStats stats = service(repo).stats(FARMER_A);

        assertEquals(42L, stats.totalReports());
        assertEquals(7L, stats.reportsNeedingHumanReview());
    }

    // ------------------------------------------------------------------
    // Stage 6B: ownership
    // ------------------------------------------------------------------

    @Test
    void savedReportIsOwnedByTheAuthenticatedFarmer() {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);
        ArgumentCaptor<FolixScanReportEntity> captor = ArgumentCaptor.forClass(FolixScanReportEntity.class);
        when(repo.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service(repo).save(validRequest(), FARMER_B);

        FolixScanReportEntity saved = captor.getValue();
        assertNotNull(saved.getFarmer(), "every saved report has an owner");
        assertEquals(FARMER_B.id(), saved.getFarmer().getId(), "the owner is the farmer whose session saved it");
        assertNotEquals(FARMER_A.id(), saved.getFarmer().getId());
    }

    @Test
    void savingFromADiagnosisResultAlsoRecordsTheOwner() {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);
        ArgumentCaptor<FolixScanReportEntity> captor = ArgumentCaptor.forClass(FolixScanReportEntity.class);
        when(repo.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));
        DiagnosisResult diagnosis = new DiagnosisResult(
                true, DiagnosisResult.PlantPart.LEAF, "Tomato", "Early blight",
                DiagnosisResult.Severity.MODERATE, DiagnosisResult.Confidence.HIGH,
                List.of("spot"), "reasoning text", List.of("action"), false);

        service(repo).save(diagnosis, FARMER_A);

        assertEquals(FARMER_A.id(), captor.getValue().getFarmer().getId());
    }

    @Test
    void theResponseDtoNeverExposesTheOwner() {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FolixReportResponse response = service(repo).save(validRequest(), FARMER_A);

        for (java.lang.reflect.RecordComponent c : FolixReportResponse.class.getRecordComponents()) {
            String n = c.getName().toLowerCase();
            assertFalse(n.contains("farmer") || n.contains("owner") || n.contains("user"),
                    "FolixReportResponse must not expose owner information: " + c.getName());
        }
        assertFalse(response.toString().contains(FARMER_A.id().toString()));
        assertFalse(response.toString().contains(FARMER_A.email()));
    }

    @Test
    void everyOperationWithoutAFarmerFailsClosedAndTouchesNothing() {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);
        FolixFarmerRepository farmerRepo = Mockito.mock(FolixFarmerRepository.class);
        FolixReportService svc = new FolixReportService(repo, farmerRepo, Mockito.mock(FolixFarmerLocationRepository.class));
        AuthenticatedFarmer noId = new AuthenticatedFarmer(null, "x", "x@example.com", Instant.now());

        assertThrows(FolixAuthException.class, () -> svc.save(validRequest(), null));
        assertThrows(FolixAuthException.class, () -> svc.save(validRequest(), noId));
        assertThrows(FolixAuthException.class, () -> svc.getById(UUID.randomUUID(), null));
        assertThrows(FolixAuthException.class, () -> svc.list(null, 0, 10));
        assertThrows(FolixAuthException.class, () -> svc.stats(null));
        Mockito.verifyNoInteractions(repo, farmerRepo);
    }

    @Test
    void anotherFarmersReportIsIndistinguishableFromANonExistentOne() {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);
        FolixScanReportEntity bsReport = savedEntityFor(FARMER_B);
        // The repository answers "empty" for (B's report id, A) — it only matches on (id AND owner).
        when(repo.findByIdAndFarmer_Id(bsReport.getId(), FARMER_A.id())).thenReturn(Optional.empty());
        when(repo.findByIdAndFarmer_Id(bsReport.getId(), FARMER_B.id())).thenReturn(Optional.of(bsReport));
        UUID neverExisted = UUID.randomUUID();
        when(repo.findByIdAndFarmer_Id(neverExisted, FARMER_A.id())).thenReturn(Optional.empty());

        FolixReportException other = assertThrows(FolixReportException.class,
                () -> service(repo).getById(bsReport.getId(), FARMER_A));
        FolixReportException missing = assertThrows(FolixReportException.class,
                () -> service(repo).getById(neverExisted, FARMER_A));

        assertEquals(FolixReportException.Code.NOT_FOUND, other.code());
        assertEquals(FolixReportException.Code.NOT_FOUND, missing.code());
        assertEquals(other.getMessage().replace(bsReport.getId().toString(), "<id>"),
                missing.getMessage().replace(neverExisted.toString(), "<id>"),
                "the error must not reveal whether the id exists");
        assertEquals(bsReport.getId(), service(repo).getById(bsReport.getId(), FARMER_B).id(),
                "the owner can still read it");
    }

    @Test
    void readsUseTheAuthenticatedFarmersIdAndNeverTheUnscopedRepositoryMethods() {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);
        when(repo.findAllByFarmer_IdOrderByCreatedAtDesc(any(), any())).thenReturn(Page.empty());
        when(repo.findByIdAndFarmer_Id(any(), any())).thenReturn(Optional.empty());

        FolixReportService svc = service(repo);
        svc.list(FARMER_B, 2, 10);
        svc.stats(FARMER_B);
        assertThrows(FolixReportException.class, () -> svc.getById(UUID.randomUUID(), FARMER_B));

        ArgumentCaptor<Pageable> paging = ArgumentCaptor.forClass(Pageable.class);
        verify(repo).findAllByFarmer_IdOrderByCreatedAtDesc(eq(FARMER_B.id()), paging.capture());
        assertEquals(2, paging.getValue().getPageNumber());
        assertEquals(10, paging.getValue().getPageSize());
        verify(repo).countByFarmer_Id(FARMER_B.id());
        verify(repo).countByFarmer_IdAndNeedsHumanReviewTrue(FARMER_B.id());
        verify(repo).findByIdAndFarmer_Id(any(), eq(FARMER_B.id()));
        verify(repo, never()).findById(any());
        verify(repo, never()).findAll();
        verify(repo, never()).count();
    }

    private static FolixScanReportEntity savedEntityFor(AuthenticatedFarmer owner) {
        FolixReportRequest r = validRequest();
        return new FolixScanReportEntity(
                UUID.randomUUID(), farmerEntity(owner), Instant.now(), r.crop(), r.plantPart(), r.disease(),
                r.severity(), r.confidence(), r.symptoms(), r.reasoning(), r.recommendedActions(),
                r.needsHumanReview(), FolixReportStatus.RECEIVED);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static FolixReportRequest withCrop(FolixReportRequest r, String crop) {
        return new FolixReportRequest(crop, r.plantPart(), r.disease(), r.severity(), r.confidence(),
                r.symptoms(), r.reasoning(), r.recommendedActions(), r.needsHumanReview());
    }

    private static FolixReportRequest withSeverity(FolixReportRequest r, String severity) {
        return new FolixReportRequest(r.crop(), r.plantPart(), r.disease(), severity, r.confidence(),
                r.symptoms(), r.reasoning(), r.recommendedActions(), r.needsHumanReview());
    }

    private static FolixReportRequest withConfidence(FolixReportRequest r, String confidence) {
        return new FolixReportRequest(r.crop(), r.plantPart(), r.disease(), r.severity(), confidence,
                r.symptoms(), r.reasoning(), r.recommendedActions(), r.needsHumanReview());
    }

    private static FolixReportRequest withPlantPart(FolixReportRequest r, String plantPart) {
        return new FolixReportRequest(r.crop(), plantPart, r.disease(), r.severity(), r.confidence(),
                r.symptoms(), r.reasoning(), r.recommendedActions(), r.needsHumanReview());
    }

    private static FolixReportRequest withReasoning(FolixReportRequest r, String reasoning) {
        return new FolixReportRequest(r.crop(), r.plantPart(), r.disease(), r.severity(), r.confidence(),
                r.symptoms(), reasoning, r.recommendedActions(), r.needsHumanReview());
    }
}
