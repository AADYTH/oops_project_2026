package com.cropscanner.folix.auth;

import com.cropscanner.folix.location.FolixFarmerLocationRepository;
import com.cropscanner.folix.FolixExceptionHandler;
import com.cropscanner.folix.FolixReportController;
import com.cropscanner.folix.FolixReportService;
import com.cropscanner.folix.FolixScanReportEntity;
import com.cropscanner.folix.FolixScanReportRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stage 6B, end to end at the HTTP layer with TWO real farmer accounts: the REAL Spring MVC + Jackson stack,
 * the REAL {@link FolixAuthService} (real BCrypt, real session tokens, in-memory account repositories), the REAL
 * interceptors on the REAL {@link FolixWebConfig} path lists, the REAL {@link FolixReportController} and the
 * REAL {@link FolixReportService}. Only the report repository is a stand-in: an in-memory table whose scoped
 * queries filter by owner exactly as the derived queries do. {@code FolixScanReportRepositoryTest} checks the
 * real derived queries against a real (H2) database.
 *
 * Nothing about identity is sent in a request except the session cookie: every scenario below has to hold
 * with farmer A's cookie in hand.
 */
class FolixReportOwnershipHttpTest {

    private static final String PASSWORD = "correct horse 42";

    private AuthTestSupport.Store store;
    private FolixScanReportRepository reportRepo;
    /** The "database table": rows in insertion order. */
    private final List<FolixScanReportEntity> table = new ArrayList<>();
    private MockMvc mvc;

    private String tokenA;
    private String tokenB;

    @BeforeEach
    void setUp() throws Exception {
        store = new AuthTestSupport.Store();
        reportRepo = inMemoryReportRepository();
        FolixAuthSettings settings = AuthTestSupport.settings();
        FolixAuthService service = new FolixAuthService(store.farmerRepo, store.sessionRepo,
                AuthTestSupport.encoder(), settings);
        FolixSessionCookies cookies = new FolixSessionCookies(settings);

        mvc = MockMvcBuilders
                .standaloneSetup(new FolixAuthController(service, cookies), new FolixFarmerController(),
                        new FolixReportController(new FolixReportService(reportRepo, store.farmerRepo, Mockito.mock(FolixFarmerLocationRepository.class))))
                .setControllerAdvice(new FolixAuthExceptionHandler(), new FolixExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticatedFarmerArgumentResolver())
                .addPlaceholderValue("cropscanner.cors.allowed-origin", AuthTestSupport.ORIGIN)
                .addMappedInterceptors(FolixWebConfig.IDENTITY_PATHS,
                        new FolixAuthInterceptor(service, cookies, settings, false))
                .addMappedInterceptors(FolixWebConfig.PROTECTED_PATHS,
                        new FolixAuthInterceptor(service, cookies, settings, true))
                .build();

        register("Asha Nair", "asha@example.com");
        register("Bala Menon", "bala@example.com");
        tokenA = login("asha@example.com");
        tokenB = login("bala@example.com");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** In-memory stand-in for the report table implementing the scoped queries with the same semantics. */
    private FolixScanReportRepository inMemoryReportRepository() {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);
        when(repo.save(any(FolixScanReportEntity.class))).thenAnswer(inv -> {
            FolixScanReportEntity e = inv.getArgument(0);
            table.add(e);
            return e;
        });
        when(repo.findAllByFarmer_IdOrderByCreatedAtDesc(any(UUID.class), any(Pageable.class))).thenAnswer(inv -> {
            UUID farmerId = inv.getArgument(0);
            Pageable pageable = inv.getArgument(1);
            List<FolixScanReportEntity> mine = new ArrayList<>(ownedBy(farmerId));
            Collections.reverse(mine); // ties on createdAt: most recently inserted first
            mine.sort(Comparator.comparing(FolixScanReportEntity::getCreatedAt).reversed()); // stable
            int from = (int) Math.min(pageable.getOffset(), mine.size());
            int to = Math.min(from + pageable.getPageSize(), mine.size());
            return new PageImpl<>(mine.subList(from, to), pageable, mine.size());
        });
        when(repo.findByIdAndFarmer_Id(any(UUID.class), any(UUID.class))).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            UUID farmerId = inv.getArgument(1);
            return ownedBy(farmerId).stream().filter(e -> e.getId().equals(id)).findFirst();
        });
        when(repo.countByFarmer_Id(any(UUID.class)))
                .thenAnswer(inv -> (long) ownedBy(inv.<UUID>getArgument(0)).size());
        when(repo.countByFarmer_IdAndNeedsHumanReviewTrue(any(UUID.class)))
                .thenAnswer(inv -> ownedBy(inv.<UUID>getArgument(0)).stream()
                        .filter(FolixScanReportEntity::isNeedsHumanReview).count());
        return repo;
    }

    private List<FolixScanReportEntity> ownedBy(UUID farmerId) {
        return table.stream().filter(e -> e.getFarmer().getId().equals(farmerId)).toList();
    }

    private void register(String name, String email) throws Exception {
        String json = "{\"name\":\"" + name + "\",\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}";
        mvc.perform(post("/api/auth/register").contentType("application/json").content(json))
                .andExpect(status().isCreated());
    }

    private String login(String email) throws Exception {
        String json = "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}";
        MvcResult result = mvc.perform(post("/api/auth/login").contentType("application/json").content(json))
                .andExpect(status().isOk()).andReturn();
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        String prefix = "FOLIX_SESSION=";
        return setCookie.substring(prefix.length(), setCookie.indexOf(';'));
    }

    private UUID idOf(String email) {
        return store.farmers.values().stream().filter(f -> f.getEmail().equals(email)).findFirst().orElseThrow().getId();
    }

    private static Cookie session(String token) {
        return new Cookie("FOLIX_SESSION", token);
    }

    private static String reportJson(String crop, boolean needsReview) {
        return """
                {
                  "crop": "%s", "plantPart": "leaf", "disease": "Early blight", "severity": "moderate",
                  "confidence": "high", "symptoms": ["Brown concentric-ring spots"],
                  "reasoning": "Concentric target-like rings are typical of early blight.",
                  "recommendedActions": ["Remove affected leaves."], "needsHumanReview": %s
                }
                """.formatted(crop, needsReview);
    }

    /** Saves a report as the given session and returns the new report's id. */
    private UUID save(String token, String crop, boolean needsReview) throws Exception {
        MvcResult result = mvc.perform(post("/api/scan/reports").cookie(session(token))
                        .contentType("application/json").content(reportJson(crop, needsReview)))
                .andExpect(status().isCreated())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        int at = body.indexOf("\"id\":\"") + 6;
        return UUID.fromString(body.substring(at, at + 36));
    }

    // ------------------------------------------------------------------
    // 1 + 2. saving: authenticated farmer saves, and is recorded as the owner
    // ------------------------------------------------------------------

    @Test
    void anAuthenticatedFarmerSavesAReportAndIsRecordedAsItsOwner() throws Exception {
        UUID reportId = save(tokenA, "Tomato", false);

        assertEquals(1, table.size());
        FolixScanReportEntity stored = table.get(0);
        assertEquals(reportId, stored.getId());
        assertEquals(idOf("asha@example.com"), stored.getFarmer().getId(), "owner is the farmer whose session saved it");
        assertNotEquals(idOf("bala@example.com"), stored.getFarmer().getId());
    }

    @Test
    void eachFarmersReportsAreRecordedAgainstThemselves() throws Exception {
        save(tokenA, "Tomato", false);
        save(tokenB, "Chilli", false);
        save(tokenA, "Okra", false);

        assertEquals(2, ownedBy(idOf("asha@example.com")).size());
        assertEquals(1, ownedBy(idOf("bala@example.com")).size());
        assertTrue(ownedBy(idOf("bala@example.com")).stream().allMatch(e -> e.getCrop().equals("Chilli")));
    }

    @Test
    void theSaveResponseCarriesNoOwnerInformation() throws Exception {
        mvc.perform(post("/api/scan/reports").cookie(session(tokenA))
                        .contentType("application/json").content(reportJson("Tomato", false)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.farmer").doesNotExist())
                .andExpect(jsonPath("$.farmerId").doesNotExist())
                .andExpect(jsonPath("$.owner").doesNotExist())
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.status").value("RECEIVED"));
    }

    // ------------------------------------------------------------------
    // 3 + 4. each farmer lists their own
    // ------------------------------------------------------------------

    @Test
    void farmerAListsOnlyTheirOwnReportsAndFarmerBListsOnlyTheirs() throws Exception {
        save(tokenA, "Tomato", false);
        save(tokenB, "Chilli", false);
        save(tokenA, "Okra", false);

        mvc.perform(get("/api/scan/reports").cookie(session(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.reports.length()").value(2))
                .andExpect(jsonPath("$.reports[?(@.crop == 'Chilli')]").isEmpty());

        mvc.perform(get("/api/scan/reports").cookie(session(tokenB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.reports.length()").value(1))
                .andExpect(jsonPath("$.reports[0].crop").value("Chilli"));
    }

    @Test
    void aFarmerWithNoReportsSeesAnEmptyHistoryEvenThoughOthersHaveSome() throws Exception {
        save(tokenA, "Tomato", false);

        mvc.perform(get("/api/scan/reports").cookie(session(tokenB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reports.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/scan/reports/summary").cookie(session(tokenB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalReports").value(0))
                .andExpect(jsonPath("$.reportsNeedingHumanReview").value(0));
    }

    @Test
    void historyStaysNewestFirst() throws Exception {
        UUID first = save(tokenA, "Tomato", false);
        Thread.sleep(5);
        UUID second = save(tokenA, "Okra", false);
        Thread.sleep(5);
        UUID third = save(tokenA, "Brinjal", false);

        mvc.perform(get("/api/scan/reports").cookie(session(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reports[0].id").value(third.toString()))
                .andExpect(jsonPath("$.reports[1].id").value(second.toString()))
                .andExpect(jsonPath("$.reports[2].id").value(first.toString()));
    }

    // ------------------------------------------------------------------
    // 5. farmer A cannot retrieve farmer B's report
    // ------------------------------------------------------------------

    @Test
    void farmerACannotRetrieveFarmerBsReportByItsId() throws Exception {
        UUID bsReport = save(tokenB, "Chilli", false);

        MvcResult other = mvc.perform(get("/api/scan/reports/" + bsReport).cookie(session(tokenA)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.crop").doesNotExist())
                .andExpect(jsonPath("$.reasoning").doesNotExist())
                .andReturn();
        String body = other.getResponse().getContentAsString();
        assertFalse(body.contains("Chilli"), "the other farmer's report contents must not leak");
        assertFalse(body.contains("Concentric"), "the other farmer's report contents must not leak");

        // ...while the owner reads it fine
        mvc.perform(get("/api/scan/reports/" + bsReport).cookie(session(tokenB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.crop").value("Chilli"));
    }

    @Test
    void anotherFarmersReportGivesTheSameAnswerAsAReportThatDoesNotExist() throws Exception {
        UUID bsReport = save(tokenB, "Chilli", false);
        UUID neverExisted = UUID.randomUUID();

        String forOthers = mvc.perform(get("/api/scan/reports/" + bsReport).cookie(session(tokenA)))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        String forMissing = mvc.perform(get("/api/scan/reports/" + neverExisted).cookie(session(tokenA)))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();

        assertEquals(forOthers.replace(bsReport.toString(), "<id>"), forMissing.replace(neverExisted.toString(), "<id>"),
                "a 404 for someone else's report must be indistinguishable from a 404 for a missing one");
    }

    // ------------------------------------------------------------------
    // 6. farmer A cannot manipulate a request to become farmer B
    // ------------------------------------------------------------------

    @Test
    void savingWithFarmerBsIdInTheBodyTheQueryAndAHeaderStillOwnsTheReportByFarmerA() throws Exception {
        UUID b = idOf("bala@example.com");
        String tampered = "{\"farmerId\":\"" + b + "\",\"owner\":\"" + b + "\",\"userId\":\"" + b + "\","
                + "\"farmer\":{\"id\":\"" + b + "\",\"email\":\"bala@example.com\"},"
                + reportJson("Tomato", false).trim().substring(1);

        mvc.perform(post("/api/scan/reports").cookie(session(tokenA))
                        .param("farmerId", b.toString())
                        .header("X-Farmer-Id", b.toString())
                        .contentType("application/json").content(tampered))
                .andExpect(status().isCreated());

        assertEquals(1, table.size());
        assertEquals(idOf("asha@example.com"), table.get(0).getFarmer().getId());
        assertTrue(ownedBy(b).isEmpty(), "farmer B must not have received a report that farmer A saved");
    }

    @Test
    void readingWithFarmerBsIdInTheQueryOrHeaderStillShowsOnlyFarmerAsData() throws Exception {
        UUID b = idOf("bala@example.com");
        UUID bsReport = save(tokenB, "Chilli", false);
        save(tokenA, "Tomato", false);

        mvc.perform(get("/api/scan/reports").cookie(session(tokenA))
                        .param("farmerId", b.toString()).param("userId", b.toString())
                        .header("X-Farmer-Id", b.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.reports[0].crop").value("Tomato"));
        mvc.perform(get("/api/scan/reports/summary").cookie(session(tokenA))
                        .param("farmerId", b.toString()).header("X-Farmer-Id", b.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalReports").value(1));
        mvc.perform(get("/api/scan/reports/" + bsReport).cookie(session(tokenA))
                        .param("farmerId", b.toString()).header("X-Farmer-Id", b.toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void aFarmersCookieCannotBeSwappedForAnotherFarmersById() throws Exception {
        save(tokenB, "Chilli", false);

        // Nothing but a valid session token identifies a farmer: a guessed/edited token is simply refused.
        String forged = tokenA.substring(0, tokenA.length() - 1) + (tokenA.endsWith("A") ? "B" : "A");
        mvc.perform(get("/api/scan/reports").cookie(session(forged))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/scan/reports").cookie(session(idOf("bala@example.com").toString())))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // 7. summary counts are scoped
    // ------------------------------------------------------------------

    @Test
    void summaryCountsAreCalculatedOnlyFromTheAuthenticatedFarmersReports() throws Exception {
        save(tokenA, "Tomato", true);
        save(tokenA, "Okra", false);
        save(tokenA, "Brinjal", false);
        save(tokenB, "Chilli", true);
        save(tokenB, "Chilli", true);

        mvc.perform(get("/api/scan/reports/summary").cookie(session(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalReports").value(3))
                .andExpect(jsonPath("$.reportsNeedingHumanReview").value(1));
        mvc.perform(get("/api/scan/reports/summary").cookie(session(tokenB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalReports").value(2))
                .andExpect(jsonPath("$.reportsNeedingHumanReview").value(2));
    }

    // ------------------------------------------------------------------
    // 8. pagination stays user-scoped
    // ------------------------------------------------------------------

    @Test
    void paginationIsScopedToTheAuthenticatedFarmer() throws Exception {
        for (int i = 0; i < 5; i++) save(tokenA, "A-crop-" + i, false);
        for (int i = 0; i < 3; i++) save(tokenB, "B-crop-" + i, false);

        for (int page = 0; page < 3; page++) {
            MvcResult r = mvc.perform(get("/api/scan/reports").cookie(session(tokenA))
                            .param("page", String.valueOf(page)).param("size", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(5))
                    .andExpect(jsonPath("$.totalPages").value(3))
                    .andExpect(jsonPath("$.page").value(page))
                    .andExpect(jsonPath("$.size").value(2))
                    .andReturn();
            assertFalse(r.getResponse().getContentAsString().contains("B-crop-"),
                    "no page of A's history contains B's reports");
        }
        mvc.perform(get("/api/scan/reports").cookie(session(tokenA)).param("page", "2").param("size", "2"))
                .andExpect(jsonPath("$.reports.length()").value(1));
        mvc.perform(get("/api/scan/reports").cookie(session(tokenA)).param("page", "3").param("size", "2"))
                .andExpect(jsonPath("$.reports.length()").value(0));

        mvc.perform(get("/api/scan/reports").cookie(session(tokenB)).param("page", "0").param("size", "2"))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.reports.length()").value(2));
    }

    // ------------------------------------------------------------------
    // 9. unauthenticated access is still rejected (and creates nothing)
    // ------------------------------------------------------------------

    @Test
    void unauthenticatedRequestsAreRejectedAndNoAnonymousReportIsCreated() throws Exception {
        UUID bsReport = save(tokenB, "Chilli", false);
        int rowsBefore = table.size();
        Mockito.clearInvocations(reportRepo);

        mvc.perform(post("/api/scan/reports").contentType("application/json").content(reportJson("Tomato", false)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/scan/reports")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/scan/reports/summary")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/scan/reports/" + bsReport)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/scan/reports").cookie(session("garbage"))
                        .contentType("application/json").content(reportJson("Tomato", false)))
                .andExpect(status().isUnauthorized());

        assertEquals(rowsBefore, table.size(), "an unauthenticated save must not create an anonymous report");
        verifyNoInteractions(reportRepo);
    }

    @Test
    void afterLogoutTheOldSessionNoLongerReachesTheReports() throws Exception {
        save(tokenA, "Tomato", false);

        mvc.perform(post("/api/auth/logout").cookie(session(tokenA))).andExpect(status().isNoContent());

        mvc.perform(get("/api/scan/reports").cookie(session(tokenA))).andExpect(status().isUnauthorized());
        // ...and logging out A did not disturb B
        mvc.perform(get("/api/scan/reports").cookie(session(tokenB))).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // 10. existing report behaviour that must still hold
    // ------------------------------------------------------------------

    @Test
    void existingValidationStillAppliesToAnAuthenticatedSave() throws Exception {
        String blankCrop = reportJson("  ", false);
        String lowConfidenceNotFlagged = reportJson("Tomato", false).replace("\"high\"", "\"low\"");

        mvc.perform(post("/api/scan/reports").cookie(session(tokenA)).contentType("application/json").content(blankCrop))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/scan/reports").cookie(session(tokenA)).contentType("application/json")
                        .content(lowConfidenceNotFlagged))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/scan/reports").cookie(session(tokenA)).contentType("application/json").content("not json"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/scan/reports/not-a-uuid").cookie(session(tokenA)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertTrue(table.isEmpty(), "nothing invalid is ever stored");
    }

    @Test
    void detailReturnsTheFullReportToItsOwner() throws Exception {
        UUID id = save(tokenA, "Tomato", true);

        mvc.perform(get("/api/scan/reports/" + id).cookie(session(tokenA)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.crop").value("Tomato"))
                .andExpect(jsonPath("$.symptoms[0]").value("Brown concentric-ring spots"))
                .andExpect(jsonPath("$.reasoning").isNotEmpty())
                .andExpect(jsonPath("$.recommendedActions[0]").value("Remove affected leaves."))
                .andExpect(jsonPath("$.needsHumanReview").value(true))
                .andExpect(jsonPath("$.farmer").doesNotExist())
                .andExpect(jsonPath("$.farmerId").doesNotExist());
    }
}
