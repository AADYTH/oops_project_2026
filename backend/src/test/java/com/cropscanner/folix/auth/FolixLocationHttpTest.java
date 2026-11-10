package com.cropscanner.folix.auth;

import com.cropscanner.folix.FolixExceptionHandler;
import com.cropscanner.folix.FolixReportController;
import com.cropscanner.folix.FolixReportService;
import com.cropscanner.folix.FolixScanReportEntity;
import com.cropscanner.folix.FolixScanReportRepository;
import com.cropscanner.folix.location.FolixFarmerLocationEntity;
import com.cropscanner.folix.location.FolixFarmerLocationRepository;
import com.cropscanner.folix.location.FolixLocationController;
import com.cropscanner.folix.location.FolixLocationExceptionHandler;
import com.cropscanner.folix.location.FolixLocationService;
import com.cropscanner.folix.map.FolixDiseaseMapController;
import com.cropscanner.folix.map.FolixDiseaseMapRepository;
import com.cropscanner.folix.map.FolixDiseaseMapService;
import com.cropscanner.folix.map.FolixMapRow;
import com.cropscanner.folix.map.FolixMapSettings;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stage 6C, end to end at the HTTP layer with THREE real farmer accounts: the real Spring MVC + Jackson stack, the real
 * {@link FolixAuthService} (real BCrypt and session tokens), the real interceptors on the real
 * {@link FolixWebConfig} path lists, and the real location / report / map controllers and services. Only the
 * repositories are in-memory stand-ins that implement the same queries; {@code FolixDiseaseMapRepositoryTest} runs
 * the real JPQL against a real (H2) database.
 *
 * Identity is only ever the session cookie. Every scenario below has to hold with nothing but a cookie in hand.
 */
class FolixLocationHttpTest {

    private static final String PASSWORD = "correct horse 42";
    // Deliberately precise coordinates: none of these digits may ever come back out of the API.
    private static final String EXACT_LAT = "11.2587";
    private static final String EXACT_LNG = "75.7804";

    private AuthTestSupport.Store store;
    private MockMvc mvc;

    /** The "report table" and the "farmer location table". */
    private final List<FolixScanReportEntity> reports = new ArrayList<>();
    private final Map<UUID, FolixFarmerLocationEntity> locations = new LinkedHashMap<>();

    private String tokenA;
    private String tokenB;
    private String tokenC;

    @BeforeEach
    void setUp() throws Exception {
        store = new AuthTestSupport.Store();
        FolixAuthSettings settings = AuthTestSupport.settings();
        FolixAuthService authService = new FolixAuthService(store.farmerRepo, store.sessionRepo,
                AuthTestSupport.encoder(), settings);
        FolixSessionCookies cookies = new FolixSessionCookies(settings);
        FolixMapSettings mapSettings = new FolixMapSettings(100, 3, 3, 30);

        FolixScanReportRepository reportRepo = inMemoryReportRepository();
        FolixFarmerLocationRepository locationRepo = inMemoryLocationRepository();
        FolixDiseaseMapRepository mapRepo = inMemoryMapRepository();

        mvc = MockMvcBuilders
                .standaloneSetup(new FolixAuthController(authService, cookies), new FolixFarmerController(),
                        new FolixLocationController(new FolixLocationService(locationRepo, mapSettings)),
                        new FolixReportController(new FolixReportService(reportRepo, store.farmerRepo, locationRepo)),
                        new FolixDiseaseMapController(new FolixDiseaseMapService(mapRepo, mapSettings)))
                .setControllerAdvice(new FolixAuthExceptionHandler(), new FolixExceptionHandler(),
                        new FolixLocationExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticatedFarmerArgumentResolver())
                .addPlaceholderValue("cropscanner.cors.allowed-origin", AuthTestSupport.ORIGIN)
                .addMappedInterceptors(FolixWebConfig.IDENTITY_PATHS,
                        new FolixAuthInterceptor(authService, cookies, settings, false))
                .addMappedInterceptors(FolixWebConfig.PROTECTED_PATHS,
                        new FolixAuthInterceptor(authService, cookies, settings, true))
                .build();

        register("Asha Nair", "asha@example.com");
        register("Bala Menon", "bala@example.com");
        register("Chandra Iyer", "chandra@example.com");
        tokenA = login("asha@example.com");
        tokenB = login("bala@example.com");
        tokenC = login("chandra@example.com");
    }

    // ------------------------------------------------------------------ in-memory stand-ins

    private FolixScanReportRepository inMemoryReportRepository() {
        FolixScanReportRepository repo = Mockito.mock(FolixScanReportRepository.class);
        when(repo.save(any(FolixScanReportEntity.class))).thenAnswer(inv -> {
            FolixScanReportEntity e = inv.getArgument(0);
            reports.add(e);
            return e;
        });
        when(repo.findByIdAndFarmer_Id(any(UUID.class), any(UUID.class))).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            UUID farmerId = inv.getArgument(1);
            return reports.stream()
                    .filter(e -> e.getId().equals(id) && e.getFarmer().getId().equals(farmerId)).findFirst();
        });
        when(repo.findAllByFarmer_IdOrderByCreatedAtDesc(any(UUID.class), any(Pageable.class))).thenAnswer(inv -> {
            UUID farmerId = inv.getArgument(0);
            List<FolixScanReportEntity> mine = reports.stream().filter(e -> e.getFarmer().getId().equals(farmerId)).toList();
            return new PageImpl<>(mine, inv.<Pageable>getArgument(1), mine.size());
        });
        return repo;
    }

    private FolixFarmerLocationRepository inMemoryLocationRepository() {
        FolixFarmerLocationRepository repo = Mockito.mock(FolixFarmerLocationRepository.class);
        when(repo.findById(any(UUID.class)))
                .thenAnswer(inv -> Optional.ofNullable(locations.get(inv.<UUID>getArgument(0))));
        when(repo.save(any(FolixFarmerLocationEntity.class))).thenAnswer(inv -> {
            FolixFarmerLocationEntity e = inv.getArgument(0);
            locations.put(e.getFarmerId(), e);
            return e;
        });
        doAnswer(inv -> {
            locations.remove(inv.<UUID>getArgument(0));
            return null;
        }).when(repo).deleteById(any(UUID.class));
        when(repo.clearRegionFromReportsOf(any(UUID.class))).thenAnswer(inv -> {
            UUID farmerId = inv.getArgument(0);
            int cleared = 0;
            for (FolixScanReportEntity e : reports) {
                if (e.getFarmer().getId().equals(farmerId) && e.hasRegion()) {
                    e.clearRegion();
                    cleared++;
                }
            }
            return cleared;
        });
        return repo;
    }

    /** Same selection as the JPQL: reports that carry a region and are inside the window. */
    private FolixDiseaseMapRepository inMemoryMapRepository() {
        FolixDiseaseMapRepository repo = Mockito.mock(FolixDiseaseMapRepository.class);
        when(repo.findLocatedRowsSince(any(Instant.class))).thenAnswer(inv -> {
            Instant since = inv.getArgument(0);
            return reports.stream()
                    .filter(e -> e.hasRegion() && !e.getCreatedAt().isBefore(since))
                    .map(e -> new FolixMapRow(e.getRegionLatMdeg(), e.getRegionLngMdeg(), e.getCrop(), e.getDisease(),
                            e.getSeverity(), e.getConfidence(), e.getFarmer().getId()))
                    .toList();
        });
        return repo;
    }

    // ------------------------------------------------------------------ helpers

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

    private static String optInJson(String lat, String lng) {
        return "{\"consent\":true,\"latitude\":" + lat + ",\"longitude\":" + lng + "}";
    }

    private MvcResult optIn(String token, String lat, String lng) throws Exception {
        return mvc.perform(put("/api/farmer/location").cookie(session(token))
                        .contentType("application/json").content(optInJson(lat, lng)))
                .andExpect(status().isOk()).andReturn();
    }

    private static String reportJson() {
        return """
                {
                  "crop": "Tomato", "plantPart": "leaf", "disease": "Early blight", "severity": "moderate",
                  "confidence": "high", "symptoms": ["Brown concentric-ring spots"],
                  "reasoning": "Concentric target-like rings are typical of early blight.",
                  "recommendedActions": ["Remove affected leaves."], "needsHumanReview": false
                }
                """;
    }

    private MvcResult saveReport(String token) throws Exception {
        return mvc.perform(post("/api/scan/reports").cookie(session(token))
                        .contentType("application/json").content(reportJson()))
                .andExpect(status().isCreated()).andReturn();
    }

    private String mapJson() throws Exception {
        return mvc.perform(get("/api/folix/disease-map")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private FolixScanReportEntity lastReportOf(String email) {
        UUID id = idOf(email);
        List<FolixScanReportEntity> mine = reports.stream().filter(e -> e.getFarmer().getId().equals(id)).toList();
        return mine.get(mine.size() - 1);
    }

    // ------------------------------------------------------------------ 1. authentication / ownership

    @Test
    void locationEndpointsRequireASessionAndStoreNothingWithoutOne() throws Exception {
        mvc.perform(get("/api/farmer/location")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mvc.perform(put("/api/farmer/location").contentType("application/json").content(optInJson(EXACT_LAT, EXACT_LNG)))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/farmer/location")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/farmer/location").cookie(session("not-a-real-token"))
                        .contentType("application/json").content(optInJson(EXACT_LAT, EXACT_LNG)))
                .andExpect(status().isUnauthorized());
        assertTrue(locations.isEmpty(), "no location may be stored without an authenticated farmer");
    }

    @Test
    void aFarmerIdSentInTheBodyCannotAssignSomeoneElsesLocation() throws Exception {
        UUID bala = idOf("bala@example.com");
        String body = "{\"consent\":true,\"latitude\":" + EXACT_LAT + ",\"longitude\":" + EXACT_LNG
                + ",\"farmerId\":\"" + bala + "\",\"farmer_id\":\"" + bala + "\",\"owner\":\"" + bala + "\"}";

        mvc.perform(put("/api/farmer/location").cookie(session(tokenA)).contentType("application/json").content(body))
                .andExpect(status().isOk());

        assertEquals(1, locations.size());
        assertTrue(locations.containsKey(idOf("asha@example.com")), "stored for the session's farmer (A)");
        assertFalse(locations.containsKey(bala), "B's location was NOT assigned by A");
        mvc.perform(get("/api/farmer/location").cookie(session(tokenB))).andExpect(status().isOk())
                .andExpect(jsonPath("$.sharingEnabled").value(false));
    }

    @Test
    void aFarmerCanOnlyWithdrawTheirOwnLocation() throws Exception {
        optIn(tokenA, EXACT_LAT, EXACT_LNG);
        optIn(tokenB, "13.0827", "80.2707");

        mvc.perform(delete("/api/farmer/location").cookie(session(tokenB))).andExpect(status().isOk())
                .andExpect(jsonPath("$.sharingEnabled").value(false));

        assertTrue(locations.containsKey(idOf("asha@example.com")), "A's choice is untouched by B withdrawing");
        assertFalse(locations.containsKey(idOf("bala@example.com")));
    }

    @Test
    void aForeignOriginCannotChangeLocationSharing() throws Exception {
        mvc.perform(put("/api/farmer/location").cookie(session(tokenA)).header("Origin", "https://evil.example")
                        .contentType("application/json").content(optInJson(EXACT_LAT, EXACT_LNG)))
                .andExpect(status().isForbidden());
        assertTrue(locations.isEmpty());
    }

    // ------------------------------------------------------------------ 2. explicit consent + coarse storage

    @Test
    void enablingStoresOnlyACoarseRegionAndNeverReturnsTheExactCoordinates() throws Exception {
        MvcResult result = optIn(tokenA, EXACT_LAT, EXACT_LNG);
        String body = result.getResponse().getContentAsString();

        FolixFarmerLocationEntity stored = locations.get(idOf("asha@example.com"));
        assertEquals(11250, stored.getRegionLatMdeg());
        assertEquals(75750, stored.getRegionLngMdeg());
        assertFalse(body.contains("11.2587"));
        assertFalse(body.contains("75.7804"));
        assertTrue(body.contains("\"centerLatitude\":11.25"));
        assertTrue(body.contains("\"sharingEnabled\":true"));
    }

    @Test
    void consentMustBeExplicitlyTrue() throws Exception {
        for (String body : List.of(
                "{\"latitude\":" + EXACT_LAT + ",\"longitude\":" + EXACT_LNG + "}",
                "{\"consent\":false,\"latitude\":" + EXACT_LAT + ",\"longitude\":" + EXACT_LNG + "}",
                "{\"consent\":\"yes\",\"latitude\":" + EXACT_LAT + ",\"longitude\":" + EXACT_LNG + "}")) {
            int code = mvc.perform(put("/api/farmer/location").cookie(session(tokenA))
                            .contentType("application/json").content(body))
                    .andReturn().getResponse().getStatus();
            assertEquals(400, code, body);
        }
        assertTrue(locations.isEmpty(), "nothing is stored without explicit consent");
    }

    @Test
    void invalidOrMissingCoordinatesAreRejected() throws Exception {
        for (String body : List.of(
                "{\"consent\":true}",
                "{\"consent\":true,\"latitude\":95,\"longitude\":10}",
                "{\"consent\":true,\"latitude\":10,\"longitude\":-181}",
                "{\"consent\":true,\"latitude\":\"abc\",\"longitude\":10}")) {
            int code = mvc.perform(put("/api/farmer/location").cookie(session(tokenA))
                            .contentType("application/json").content(body))
                    .andReturn().getResponse().getStatus();
            assertEquals(400, code, body);
        }
        assertTrue(locations.isEmpty());
    }

    // ------------------------------------------------------------------ 3. reports and location

    @Test
    void aReportWithoutLocationIsValidAndHasNoRegion() throws Exception {
        MvcResult result = saveReport(tokenA);
        assertNull(lastReportOf("asha@example.com").getRegionLatMdeg());
        assertFalse(lastReportOf("asha@example.com").hasRegion());
        assertTrue(result.getResponse().getContentAsString().contains("\"disease\":\"Early blight\""));
    }

    @Test
    void aReportSavedAfterOptInCarriesOnlyTheCoarseRegionAndTheApiNeverShowsIt() throws Exception {
        optIn(tokenA, EXACT_LAT, EXACT_LNG);
        MvcResult saved = saveReport(tokenA);

        FolixScanReportEntity report = lastReportOf("asha@example.com");
        assertEquals(11250, report.getRegionLatMdeg());
        assertEquals(75750, report.getRegionLngMdeg());

        String json = saved.getResponse().getContentAsString();
        assertFalse(json.toLowerCase().contains("region"));
        assertFalse(json.toLowerCase().contains("latitude"));
        assertFalse(json.contains("11.25"));
    }

    @Test
    void anEarlierReportIsNeverBackfilledWithALocationEnabledLater() throws Exception {
        saveReport(tokenA);
        FolixScanReportEntity before = lastReportOf("asha@example.com");

        optIn(tokenA, EXACT_LAT, EXACT_LNG);
        assertFalse(before.hasRegion(), "the earlier report stays location-less");

        saveReport(tokenA);
        assertTrue(lastReportOf("asha@example.com").hasRegion(), "only the new report gets the region");
    }

    @Test
    void savingNeverDependsOnLocationEvenAfterWithdrawal() throws Exception {
        optIn(tokenA, EXACT_LAT, EXACT_LNG);
        mvc.perform(delete("/api/farmer/location").cookie(session(tokenA))).andExpect(status().isOk());
        saveReport(tokenA);
        assertFalse(lastReportOf("asha@example.com").hasRegion());
    }

    @Test
    void stageSixBOwnershipStillHoldsForLocatedReports() throws Exception {
        optIn(tokenA, EXACT_LAT, EXACT_LNG);
        MvcResult saved = saveReport(tokenA);
        String body = saved.getResponse().getContentAsString();
        int at = body.indexOf("\"id\":\"") + 6;
        String reportId = body.substring(at, at + 36);

        mvc.perform(get("/api/scan/reports/" + reportId).cookie(session(tokenA))).andExpect(status().isOk());
        mvc.perform(get("/api/scan/reports/" + reportId).cookie(session(tokenB))).andExpect(status().isNotFound());
        mvc.perform(post("/api/scan/reports").contentType("application/json").content(reportJson()))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ 4. the map

    @Test
    void theMapIsPublicAndEmptyWhenNobodyHasContributed() throws Exception {
        mvc.perform(get("/api/folix/disease-map")).andExpect(status().isOk())
                .andExpect(jsonPath("$.regions").isEmpty())
                .andExpect(jsonPath("$.minimumReports").value(3))
                .andExpect(jsonPath("$.lookbackDays").value(30));
        mvc.perform(get("/api/folix/disease-map").cookie(session("garbage"))).andExpect(status().isOk());
    }

    @Test
    void reportsWithoutLocationNeverReachTheMap() throws Exception {
        saveReport(tokenA);
        saveReport(tokenB);
        saveReport(tokenC);
        mvc.perform(get("/api/folix/disease-map")).andExpect(jsonPath("$.regions").isEmpty());
    }

    @Test
    void anAreaBelowTheThresholdIsHiddenAndAppearsOnceEnoughDifferentFarmersContribute() throws Exception {
        optIn(tokenA, EXACT_LAT, EXACT_LNG);
        optIn(tokenB, "11.2001", "75.7994");     // a different farm in the same ~11 km cell
        saveReport(tokenA);
        mvc.perform(get("/api/folix/disease-map")).andExpect(jsonPath("$.regions").isEmpty());

        saveReport(tokenB);
        mvc.perform(get("/api/folix/disease-map")).andExpect(jsonPath("$.regions").isEmpty());

        // still only two farmers, even with many reports from one of them
        saveReport(tokenA);
        saveReport(tokenA);
        mvc.perform(get("/api/folix/disease-map")).andExpect(jsonPath("$.regions").isEmpty());

        optIn(tokenC, "11.2900", "75.7100");
        saveReport(tokenC);
        mvc.perform(get("/api/folix/disease-map")).andExpect(status().isOk())
                .andExpect(jsonPath("$.regions.length()").value(1))
                .andExpect(jsonPath("$.regions[0].centerLatitude").value(11.25))
                .andExpect(jsonPath("$.regions[0].centerLongitude").value(75.75))
                .andExpect(jsonPath("$.regions[0].totalReports").value(5))
                .andExpect(jsonPath("$.regions[0].diseases[0].crop").value("Tomato"))
                .andExpect(jsonPath("$.regions[0].diseases[0].disease").value("Early blight"))
                .andExpect(jsonPath("$.regions[0].diseases[0].reportCount").value(5))
                .andExpect(jsonPath("$.regions[0].diseases[0].severity.moderate").value(5));
    }

    @Test
    void oneFarmerWithManyReportsNeverExposesTheirRegion() throws Exception {
        optIn(tokenA, EXACT_LAT, EXACT_LNG);
        for (int i = 0; i < 12; i++) {
            saveReport(tokenA);
        }
        mvc.perform(get("/api/folix/disease-map")).andExpect(jsonPath("$.regions").isEmpty());
    }

    @Test
    void theMapNeverContainsFarmerIdentityOrExactCoordinates() throws Exception {
        optIn(tokenA, EXACT_LAT, EXACT_LNG);
        optIn(tokenB, "11.2001", "75.7994");
        optIn(tokenC, "11.2900", "75.7100");
        saveReport(tokenA);
        saveReport(tokenB);
        saveReport(tokenC);

        String json = mapJson();
        assertTrue(json.contains("\"regions\":[{"), "the area is present, so this is a real check: " + json);
        for (String email : List.of("asha@example.com", "bala@example.com", "chandra@example.com")) {
            assertFalse(json.contains(email));
            assertFalse(json.contains(idOf(email).toString()));
        }
        for (String name : List.of("Asha", "Bala", "Chandra")) {
            assertFalse(json.contains(name));
        }
        for (String exact : List.of("11.2587", "75.7804", "11.2001", "75.7994", "11.29", "75.71")) {
            assertFalse(json.contains(exact), "exact coordinate leaked: " + exact);
        }
        String probe = json.replace("centerLatitude", "").replace("centerLongitude", "").toLowerCase();
        for (String forbidden : List.of("farmer", "email", "session", "token", "latitude", "longitude", "\"id\"")) {
            assertFalse(probe.contains(forbidden), "unexpected field: " + forbidden);
        }
    }

    @Test
    void withdrawingConsentRemovesTheFarmersContributionButKeepsTheirReports() throws Exception {
        optIn(tokenA, EXACT_LAT, EXACT_LNG);
        optIn(tokenB, EXACT_LAT, EXACT_LNG);
        optIn(tokenC, EXACT_LAT, EXACT_LNG);
        saveReport(tokenA);
        saveReport(tokenB);
        MvcResult cReport = saveReport(tokenC);
        mvc.perform(get("/api/folix/disease-map")).andExpect(jsonPath("$.regions.length()").value(1));

        mvc.perform(delete("/api/farmer/location").cookie(session(tokenC))).andExpect(status().isOk());

        mvc.perform(get("/api/folix/disease-map")).andExpect(jsonPath("$.regions").isEmpty());
        assertFalse(lastReportOf("chandra@example.com").hasRegion());
        String body = cReport.getResponse().getContentAsString();
        String reportId = body.substring(body.indexOf("\"id\":\"") + 6, body.indexOf("\"id\":\"") + 42);
        mvc.perform(get("/api/scan/reports/" + reportId).cookie(session(tokenC))).andExpect(status().isOk());
    }

    @Test
    void existingAuthenticationBehaviourIsUnchanged() throws Exception {
        mvc.perform(get("/api/farmer/profile")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/farmer/profile").cookie(session(tokenA))).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("asha@example.com"));
        mvc.perform(get("/api/auth/session").cookie(session(tokenA))).andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true));
    }
}
