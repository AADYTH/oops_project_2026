package com.cropscanner.folix;

import com.cropscanner.folix.location.FolixFarmerLocationRepository;
import com.cropscanner.folix.auth.AuthenticatedFarmer;
import com.cropscanner.folix.auth.AuthenticatedFarmerArgumentResolver;
import com.cropscanner.folix.auth.FolixAuthInterceptor;
import com.cropscanner.folix.auth.FolixFarmerEntity;
import com.cropscanner.folix.auth.FolixFarmerRepository;
import com.cropscanner.folix.auth.FolixFarmerStatus;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-level check of {@code POST /api/scan/reports} with the REAL Spring MVC +
 * Jackson stack and the REAL {@link FolixReportService} (only the JPA
 * repository underneath it is mocked, exactly as {@link FolixReportServiceTest}
 * does). Confirms the controller is a thin, correct wrapper around the
 * existing persistence foundation: valid diagnoses save, invalid ones are
 * rejected with the standard FOLIX error envelope, and the JPA entity never
 * appears on the wire.
 */
class FolixReportControllerTest {

    private static final AuthenticatedFarmer FARMER = new AuthenticatedFarmer(
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), "Asha Nair", "asha@example.com",
            Instant.parse("2026-09-01T00:00:00Z"));

    private static FolixFarmerEntity farmerEntity() {
        return new FolixFarmerEntity(FARMER.id(), FARMER.name(), FARMER.email(), "hash",
                FolixFarmerStatus.ACTIVE, FARMER.createdAt());
    }

    /** Real MVC stack, real AuthenticatedFarmerArgumentResolver (the interceptor is covered in the auth tests). */
    private MockMvc mvc(FolixScanReportRepository repo) {
        FolixFarmerRepository farmers = Mockito.mock(FolixFarmerRepository.class);
        when(farmers.getReferenceById(FARMER.id())).thenReturn(farmerEntity());
        return MockMvcBuilders.standaloneSetup(new FolixReportController(new FolixReportService(repo, farmers, Mockito.mock(FolixFarmerLocationRepository.class))))
                .setControllerAdvice(new FolixExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticatedFarmerArgumentResolver())
                .build();
    }

    /** Puts the authenticated farmer on the request exactly the way FolixAuthInterceptor does after checking the cookie. */
    private static RequestPostProcessor asFarmer() {
        return request -> {
            request.setAttribute(FolixAuthInterceptor.FARMER_ATTRIBUTE, FARMER);
            return request;
        };
    }

    private static final String VALID_JSON = """
            {
              "crop": "Tomato",
              "plantPart": "leaf",
              "disease": "Early blight",
              "severity": "moderate",
              "confidence": "high",
              "symptoms": ["Brown concentric-ring spots", "Yellow halo around lesions"],
              "reasoning": "Concentric target-like rings are typical of early blight.",
              "recommendedActions": ["Remove affected leaves.", "Ask a local agricultural officer to confirm."],
              "needsHumanReview": false
            }
            """;

    // ------------------------------------------------------------------
    // 1. a valid diagnosis can be saved
    // ------------------------------------------------------------------

    @Test
    void validReportIsSavedAndTheExistingResponseDtoIsReturned() throws Exception {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mvc(repo).perform(post("/api/scan/reports").with(asFarmer()).contentType("application/json").content(VALID_JSON))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.crop").value("Tomato"))
                .andExpect(jsonPath("$.disease").value("Early blight"))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.needsHumanReview").value(false))
                // the JPA entity is never exposed: only the response DTO's own fields appear
                .andExpect(jsonPath("$.symptoms.length()").value(2));
    }

    // ------------------------------------------------------------------
    // 2. invalid report data is rejected
    // ------------------------------------------------------------------

    @Test
    void blankCropIsRejectedWithFolixErrorEnvelope() throws Exception {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);
        String bad = VALID_JSON.replace("\"Tomato\"", "\"  \"");

        mvc(repo).perform(post("/api/scan/reports").with(asFarmer()).contentType("application/json").content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.retryable").value(false));
        Mockito.verifyNoInteractions(repo);
    }

    @Test
    void invalidSeverityEnumIsRejected() throws Exception {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);
        String bad = VALID_JSON.replace("\"moderate\"", "\"catastrophic\"");

        mvc(repo).perform(post("/api/scan/reports").with(asFarmer()).contentType("application/json").content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        Mockito.verifyNoInteractions(repo);
    }

    @Test
    void lowConfidenceWithoutHumanReviewIsRejectedByTheExistingInvariant() throws Exception {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);
        // needsHumanReview stays false while confidence drops to low: violates FolixReportService's invariant.
        String bad = VALID_JSON.replace("\"high\"", "\"low\"");

        mvc(repo).perform(post("/api/scan/reports").with(asFarmer()).contentType("application/json").content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        Mockito.verifyNoInteractions(repo);
    }

    @Test
    void malformedJsonBodyIsRejectedNotServerError() throws Exception {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);

        mvc(repo).perform(post("/api/scan/reports").with(asFarmer()).contentType("application/json").content("not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        Mockito.verifyNoInteractions(repo);
    }

    @Test
    void missingBodyIsRejectedNotServerError() throws Exception {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);

        mvc(repo).perform(post("/api/scan/reports").with(asFarmer()).contentType("application/json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        Mockito.verifyNoInteractions(repo);
    }

    // ------------------------------------------------------------------
    // unknown-disease reports (routed from the Scanner's uncertain state) still save
    // ------------------------------------------------------------------

    @Test
    void unknownDiseaseWithHumanReviewFlaggedIsAccepted() throws Exception {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        String json = """
                {
                  "crop": "unknown",
                  "plantPart": "unknown",
                  "disease": "unknown",
                  "severity": "unknown",
                  "confidence": "low",
                  "symptoms": [],
                  "reasoning": "The photo shows no visible plant.",
                  "recommendedActions": [],
                  "needsHumanReview": true
                }
                """;

        mvc(repo).perform(post("/api/scan/reports").with(asFarmer()).contentType("application/json").content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.disease").value("unknown"))
                .andExpect(jsonPath("$.needsHumanReview").value(true));
    }

    // ------------------------------------------------------------------
    // sanity: the response is the real FolixReportResponse DTO, not the entity
    // ------------------------------------------------------------------

    @Test
    void responseIdIsARealUuidGeneratedByTheService() throws Exception {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mvc(repo).perform(post("/api/scan/reports").with(asFarmer()).contentType("application/json").content(VALID_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.matchesPattern(
                        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")));
    }

    // ------------------------------------------------------------------
    // Stage 5C: GET /api/scan/reports, /reports/summary, /reports/{id} —
    // the read side the Dashboard uses. Same real-service/mocked-repository
    // setup as the save() tests above.
    // ------------------------------------------------------------------

    private static FolixScanReportEntity sampleEntity() {
        return new FolixScanReportEntity(
                UUID.randomUUID(),
                farmerEntity(),
                Instant.parse("2026-09-01T10:15:30Z"),
                "Tomato",
                "leaf",
                "Early blight",
                "moderate",
                "high",
                List.of("Brown concentric-ring spots"),
                "Concentric target-like rings are typical of early blight.",
                List.of("Remove affected leaves."),
                false,
                FolixReportStatus.RECEIVED
        );
    }

    @Test
    void listReturnsPagedReportsFromTheExistingService() throws Exception {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);
        Page<FolixScanReportEntity> page = new PageImpl<>(List.of(sampleEntity()), PageRequest.of(0, 20), 1);
        when(repo.findAllByFarmer_IdOrderByCreatedAtDesc(eq(FARMER.id()), any())).thenReturn(page);

        mvc(repo).perform(get("/api/scan/reports").with(asFarmer()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.reports.length()").value(1))
                .andExpect(jsonPath("$.reports[0].crop").value("Tomato"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.page").value(0));
    }

    @Test
    void listWithNoSavedReportsReturnsAnEmptyPageNotAnError() throws Exception {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);
        Page<FolixScanReportEntity> empty = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(repo.findAllByFarmer_IdOrderByCreatedAtDesc(eq(FARMER.id()), any())).thenReturn(empty);

        mvc(repo).perform(get("/api/scan/reports").with(asFarmer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reports.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void summaryReturnsTheExistingStatsCounts() throws Exception {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);
        when(repo.countByFarmer_Id(FARMER.id())).thenReturn(12L);
        when(repo.countByFarmer_IdAndNeedsHumanReviewTrue(FARMER.id())).thenReturn(3L);

        mvc(repo).perform(get("/api/scan/reports/summary").with(asFarmer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalReports").value(12))
                .andExpect(jsonPath("$.reportsNeedingHumanReview").value(3));
    }

    @Test
    void getByIdReturnsTheMatchingReport() throws Exception {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);
        FolixScanReportEntity entity = sampleEntity();
        when(repo.findByIdAndFarmer_Id(entity.getId(), FARMER.id())).thenReturn(java.util.Optional.of(entity));

        mvc(repo).perform(get("/api/scan/reports/" + entity.getId()).with(asFarmer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(entity.getId().toString()))
                .andExpect(jsonPath("$.disease").value("Early blight"))
                .andExpect(jsonPath("$.reasoning").isNotEmpty());
    }

    @Test
    void getByIdUnknownIdIsA404WithTheStandardEnvelope() throws Exception {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);
        UUID missing = UUID.randomUUID();
        when(repo.findByIdAndFarmer_Id(missing, FARMER.id())).thenReturn(java.util.Optional.empty());

        mvc(repo).perform(get("/api/scan/reports/" + missing).with(asFarmer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void getByIdMalformedUuidIsA400NotA500() throws Exception {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);

        mvc(repo).perform(get("/api/scan/reports/not-a-uuid").with(asFarmer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        Mockito.verifyNoInteractions(repo);
    }

    // ------------------------------------------------------------------
    // Stage 6B: the owner is derived from the session, never from the request
    // ------------------------------------------------------------------

    @Test
    void savedReportIsOwnedByTheSessionFarmerAndTheOwnerIsNotInTheResponse() throws Exception {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);
        org.mockito.ArgumentCaptor<FolixScanReportEntity> saved =
                org.mockito.ArgumentCaptor.forClass(FolixScanReportEntity.class);
        when(repo.save(saved.capture())).thenAnswer(inv -> inv.getArgument(0));

        mvc(repo).perform(post("/api/scan/reports").with(asFarmer()).contentType("application/json").content(VALID_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.farmer").doesNotExist())
                .andExpect(jsonPath("$.farmerId").doesNotExist())
                .andExpect(jsonPath("$.owner").doesNotExist())
                .andExpect(jsonPath("$.userId").doesNotExist());

        assertEquals(FARMER.id(), saved.getValue().getFarmer().getId());
    }

    @Test
    void aBrowserSuppliedOwnerInBodyQueryOrHeaderIsIgnored() throws Exception {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);
        org.mockito.ArgumentCaptor<FolixScanReportEntity> saved =
                org.mockito.ArgumentCaptor.forClass(FolixScanReportEntity.class);
        when(repo.save(saved.capture())).thenAnswer(inv -> inv.getArgument(0));
        UUID someoneElse = UUID.randomUUID();
        // The same valid diagnosis, plus every owner-like property a hostile client could think of.
        String tampered = "{\"farmerId\": \"" + someoneElse + "\", \"owner\": \"" + someoneElse + "\", "
                + "\"farmer\": {\"id\": \"" + someoneElse + "\"}, \"userId\": \"" + someoneElse + "\", "
                + VALID_JSON.substring(VALID_JSON.indexOf('{') + 1);

        mvc(repo).perform(post("/api/scan/reports").with(asFarmer())
                        .param("farmerId", someoneElse.toString())
                        .header("X-Farmer-Id", someoneElse.toString())
                        .contentType("application/json").content(tampered))
                .andExpect(status().isCreated());

        assertEquals(FARMER.id(), saved.getValue().getFarmer().getId(),
                "ownership comes from the session, whatever the client claims");
    }

    @Test
    void listAndSummaryAndDetailIgnoreABrowserSuppliedFarmerId() throws Exception {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);
        UUID someoneElse = UUID.randomUUID();
        when(repo.findAllByFarmer_IdOrderByCreatedAtDesc(eq(FARMER.id()), any()))
                .thenReturn(new PageImpl<>(List.of(sampleEntity()), PageRequest.of(0, 20), 1));
        when(repo.countByFarmer_Id(FARMER.id())).thenReturn(1L);
        when(repo.countByFarmer_IdAndNeedsHumanReviewTrue(FARMER.id())).thenReturn(0L);
        when(repo.findByIdAndFarmer_Id(any(), eq(FARMER.id()))).thenReturn(java.util.Optional.empty());

        MockMvc mvc = mvc(repo);
        mvc.perform(get("/api/scan/reports").with(asFarmer()).param("farmerId", someoneElse.toString())
                        .header("X-Farmer-Id", someoneElse.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/scan/reports/summary").with(asFarmer()).param("farmerId", someoneElse.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalReports").value(1));
        mvc.perform(get("/api/scan/reports/" + UUID.randomUUID()).with(asFarmer()).param("farmerId", someoneElse.toString()))
                .andExpect(status().isNotFound());

        Mockito.verify(repo, Mockito.never()).findAllByFarmer_IdOrderByCreatedAtDesc(eq(someoneElse), any());
        Mockito.verify(repo, Mockito.never()).countByFarmer_Id(someoneElse);
        Mockito.verify(repo, Mockito.never()).findByIdAndFarmer_Id(any(), eq(someoneElse));
    }

    @Test
    void aReportOwnedBySomeoneElseIsA404WithTheSameEnvelopeAsAMissingOne() throws Exception {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);
        FolixScanReportEntity entity = sampleEntity();
        // the repository only matches on (id AND owner): for this farmer it finds nothing
        when(repo.findByIdAndFarmer_Id(entity.getId(), FARMER.id())).thenReturn(java.util.Optional.empty());

        mvc(repo).perform(get("/api/scan/reports/" + entity.getId()).with(asFarmer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.crop").doesNotExist())
                .andExpect(jsonPath("$.reasoning").doesNotExist());
    }

    @Test
    void withoutAFarmerOnTheRequestEveryRouteFailsClosedAs401NotAnAnonymousReportOrA500() throws Exception {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);
        MockMvc mvc = mvc(repo);

        mvc.perform(post("/api/scan/reports").contentType("application/json").content(VALID_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/scan/reports")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/scan/reports/summary")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/scan/reports/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        Mockito.verifyNoInteractions(repo);
    }
}
