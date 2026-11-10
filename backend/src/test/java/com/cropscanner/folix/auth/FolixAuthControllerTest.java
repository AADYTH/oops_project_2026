package com.cropscanner.folix.auth;

import com.cropscanner.folix.location.FolixFarmerLocationRepository;
import com.cropscanner.folix.FolixExceptionHandler;
import com.cropscanner.folix.FolixReportController;
import com.cropscanner.folix.FolixReportService;
import com.cropscanner.folix.FolixScanReportRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.server.PathContainer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-level tests of the account API with the REAL Spring MVC + Jackson stack, the REAL
 * {@link FolixAuthService} (in-memory repositories, real BCrypt), the REAL interceptors registered on the
 * REAL path lists from {@link FolixWebConfig}, and the REAL {@link FolixReportController} — so
 * "an unauthenticated request to the report API is refused" and "an authenticated one gets through" are
 * checked end to end at the HTTP layer (without booting the whole application).
 */
class FolixAuthControllerTest {

    private static final String PASSWORD = "correct horse 42";
    private static final String REGISTER_JSON =
            "{\"name\":\"Asha Nair\",\"email\":\"asha@example.com\",\"password\":\"" + PASSWORD + "\"}";
    private static final String LOGIN_JSON = "{\"email\":\"asha@example.com\",\"password\":\"" + PASSWORD + "\"}";
    private static final String REPORT_JSON = """
            {
              "crop": "Tomato", "plantPart": "leaf", "disease": "Early blight", "severity": "moderate",
              "confidence": "high", "symptoms": ["Brown concentric-ring spots"],
              "reasoning": "Concentric target-like rings are typical of early blight.",
              "recommendedActions": ["Remove affected leaves."], "needsHumanReview": false
            }
            """;

    private AuthTestSupport.Store store;
    private FolixScanReportRepository reportRepo;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        store = new AuthTestSupport.Store();
        reportRepo = Mockito.mock(FolixScanReportRepository.class);
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
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void register() throws Exception {
        mvc.perform(post("/api/auth/register").contentType("application/json").content(REGISTER_JSON))
                .andExpect(status().isCreated());
    }

    /** Logs in and returns the raw session token taken from the Set-Cookie header, as a browser would. */
    private String login() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").contentType("application/json").content(LOGIN_JSON))
                .andExpect(status().isOk())
                .andReturn();
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        String prefix = "FOLIX_SESSION=";
        return setCookie.substring(prefix.length(), setCookie.indexOf(';'));
    }

    private static Cookie session(String token) {
        return new Cookie("FOLIX_SESSION", token);
    }

    // ------------------------------------------------------------------
    // registration
    // ------------------------------------------------------------------

    @Test
    void registrationReturns201WithTheProfileAndNeverThePasswordOrHash() throws Exception {
        mvc.perform(post("/api/auth/register").contentType("application/json").content(REGISTER_JSON))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("Asha Nair"))
                .andExpect(jsonPath("$.email").value("asha@example.com"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(content().string(not(containsString(PASSWORD))))
                .andExpect(content().string(not(containsString("$2"))))
                // registering does not log the farmer in
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void duplicateRegistrationIs409() throws Exception {
        register();
        mvc.perform(post("/api/auth/register").contentType("application/json").content(REGISTER_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EMAIL_ALREADY_REGISTERED"))
                .andExpect(jsonPath("$.error.retryable").value(false));
    }

    @Test
    void invalidRegistrationIs400WithPerFieldMessages() throws Exception {
        mvc.perform(post("/api/auth/register").contentType("application/json")
                        .content("{\"name\":\"\",\"email\":\"nope\",\"password\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fields.name").isNotEmpty())
                .andExpect(jsonPath("$.error.fields.email").isNotEmpty())
                .andExpect(jsonPath("$.error.fields.password").isNotEmpty());
    }

    @Test
    void malformedOrMissingRegistrationBodyIs400NotA500() throws Exception {
        mvc.perform(post("/api/auth/register").contentType("application/json").content("not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/auth/register").contentType("application/json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    // ------------------------------------------------------------------
    // login
    // ------------------------------------------------------------------

    @Test
    void loginSetsAnHttpOnlyCookieAndKeepsTheTokenOutOfTheBody() throws Exception {
        register();

        MvcResult result = mvc.perform(post("/api/auth/login").contentType("application/json").content(LOGIN_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Set-Cookie", containsString("FOLIX_SESSION=")))
                .andExpect(header().string("Set-Cookie", containsString("HttpOnly")))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Lax")))
                .andExpect(header().string("Set-Cookie", containsString("Path=/api")))
                .andExpect(jsonPath("$.email").value("asha@example.com"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andReturn();

        String setCookie = result.getResponse().getHeader("Set-Cookie");
        String token = setCookie.substring("FOLIX_SESSION=".length(), setCookie.indexOf(';'));
        assertFalse(result.getResponse().getContentAsString().contains(token), "the session token must not appear in the body");
    }

    @Test
    void invalidCredentialsAre401WithAGenericMessageAndNoCookie() throws Exception {
        register();
        mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"email\":\"asha@example.com\",\"password\":\"wrong password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"email\":\"nobody@example.com\",\"password\":\"wrong password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void loginWithMissingFieldsIs400() throws Exception {
        mvc.perform(post("/api/auth/login").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    // ------------------------------------------------------------------
    // unauthenticated vs authenticated access
    // ------------------------------------------------------------------

    @Test
    void profileWithoutASessionIs401InTheStandardEnvelope() throws Exception {
        mvc.perform(get("/api/farmer/profile"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.error.retryable").value(false));
    }

    @Test
    void profileWithAGarbageOrForgedCookieIs401() throws Exception {
        mvc.perform(get("/api/farmer/profile").cookie(session("garbage")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/farmer/profile").cookie(session("A".repeat(43))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aBrowserSuppliedFarmerIdDoesNotAuthenticate() throws Exception {
        register();
        mvc.perform(get("/api/farmer/profile")
                        .param("farmerId", UUID.randomUUID().toString())
                        .header("X-Farmer-Id", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void profileWithAValidSessionReturnsTheAuthenticatedFarmersBasicInfo() throws Exception {
        register();
        String token = login();

        mvc.perform(get("/api/farmer/profile").cookie(session(token)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.name").value("Asha Nair"))
                .andExpect(jsonPath("$.email").value("asha@example.com"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void sessionEndpointIs200ForBothAnonymousAndLoggedInVisitors() throws Exception {
        mvc.perform(get("/api/auth/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.farmer").doesNotExist());

        register();
        String token = login();
        mvc.perform(get("/api/auth/session").cookie(session(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.farmer.email").value("asha@example.com"));
    }

    // ------------------------------------------------------------------
    // logout
    // ------------------------------------------------------------------

    @Test
    void logoutRevokesTheSessionServerSideAndClearsTheCookie() throws Exception {
        register();
        String token = login();

        mvc.perform(post("/api/auth/logout").cookie(session(token)))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));

        // the old cookie value is now worthless even if a browser (or an attacker) still has it
        mvc.perform(get("/api/farmer/profile").cookie(session(token)))
                .andExpect(status().isUnauthorized());
        assertTrue(store.sessions.isEmpty());
    }

    @Test
    void logoutWithoutASessionIsHarmless() throws Exception {
        mvc.perform(post("/api/auth/logout")).andExpect(status().isNoContent());
    }

    @Test
    void loggingInAgainReplacesTheSessionThisBrowserAlreadyHad() throws Exception {
        register();
        String first = login();

        MvcResult second = mvc.perform(post("/api/auth/login").cookie(session(first))
                        .contentType("application/json").content(LOGIN_JSON))
                .andExpect(status().isOk()).andReturn();

        assertTrue(second.getResponse().getHeader("Set-Cookie").startsWith("FOLIX_SESSION="));
        mvc.perform(get("/api/farmer/profile").cookie(session(first))).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // the existing report API is now behind the session
    // ------------------------------------------------------------------

    @Test
    void savingAReportWithoutLoggingInIs401AndNothingIsSaved() throws Exception {
        mvc.perform(post("/api/scan/reports").contentType("application/json").content(REPORT_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        verifyNoInteractions(reportRepo);
    }

    @Test
    void readingReportsWithoutLoggingInIs401ForListSummaryAndDetail() throws Exception {
        mvc.perform(get("/api/scan/reports")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/scan/reports/summary")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/scan/reports/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        verifyNoInteractions(reportRepo);
    }

    @Test
    void savingAndListingReportsWorksWhenLoggedIn() throws Exception {
        register();
        String token = login();
        when(reportRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(reportRepo.findAllByFarmer_IdOrderByCreatedAtDesc(any(UUID.class), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mvc.perform(post("/api/scan/reports").cookie(session(token))
                        .contentType("application/json").content(REPORT_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.crop").value("Tomato"));
        mvc.perform(get("/api/scan/reports").cookie(session(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // ------------------------------------------------------------------
    // what is (and is not) covered — guards "do not weaken / do not touch existing endpoints"
    // ------------------------------------------------------------------

    private static boolean matchesAny(String[] patterns, String path) {
        PathPatternParser parser = new PathPatternParser();
        for (String p : patterns) {
            if (parser.parse(p).matches(PathContainer.parsePath(path))) {
                return true;
            }
        }
        return false;
    }

    @Test
    void protectedRoutesAreExactlyTheFarmerProfileAndTheSavedReportApi() {
        for (String path : List.of("/api/farmer/profile", "/api/scan/reports", "/api/scan/reports/summary",
                "/api/scan/reports/" + UUID.randomUUID())) {
            assertTrue(matchesAny(FolixWebConfig.PROTECTED_PATHS, path), path + " should require a farmer session");
        }
    }

    @Test
    void theScannerAnalyzeEndpointTheLegacyApiAndTheAuthorityPortalAreNotTouched() {
        for (String path : List.of("/api/scan/analyze", "/api/v1/scans", "/api/v1/authorities/nearest",
                "/api/v1/outbreaks", "/h2-console/login.do", "/api/v1/feedback")) {
            assertFalse(matchesAny(FolixWebConfig.PROTECTED_PATHS, path), path + " must stay as it was");
            assertFalse(matchesAny(FolixWebConfig.IDENTITY_PATHS, path), path + " must stay as it was");
        }
    }

    @Test
    void theAuthEndpointsThemselvesArePublicButOriginChecked() {
        assertTrue(matchesAny(FolixWebConfig.IDENTITY_PATHS, "/api/auth/login"));
        assertFalse(matchesAny(FolixWebConfig.PROTECTED_PATHS, "/api/auth/login"));
    }
}
