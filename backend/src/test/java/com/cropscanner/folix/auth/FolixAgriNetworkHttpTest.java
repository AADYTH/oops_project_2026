package com.cropscanner.folix.auth;

import com.cropscanner.folix.agrinetwork.FolixAgriNetworkController;
import com.cropscanner.folix.agrinetwork.FolixAgriNetworkExceptionHandler;
import com.cropscanner.folix.agrinetwork.FolixAgriNetworkService;
import com.cropscanner.folix.agrinetwork.FolixFarmerAgriProfileEntity;
import com.cropscanner.folix.agrinetwork.FolixFarmerAgriProfileRepository;
import com.cropscanner.folix.agrinetwork.FolixSupportCategory;
import com.cropscanner.folix.agrinetwork.FolixSupportContactEntity;
import com.cropscanner.folix.agrinetwork.FolixSupportContactRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stage 6D, end to end at the HTTP layer with real farmer accounts: the real Spring MVC + Jackson stack,
 * the real {@link FolixAuthService} (real BCrypt and session tokens), the real interceptors on the real
 * {@link FolixWebConfig} path lists, and the real Agricultural Network controller/service. Only the
 * repositories are in-memory stand-ins, same convention as {@code FolixLocationHttpTest} (Stage 6C).
 *
 * Identity is only ever the session cookie. Every scenario below has to hold with nothing but a cookie in
 * hand — and never a "Forward Case" / authority endpoint in sight, which is deliberately out of scope here.
 */
class FolixAgriNetworkHttpTest {

    private static final String PASSWORD = "correct horse 42";

    private AuthTestSupport.Store store;
    private MockMvc mvc;

    private final Map<UUID, FolixFarmerAgriProfileEntity> profiles = new LinkedHashMap<>();
    private final List<FolixSupportContactEntity> contacts = new ArrayList<>();

    private String tokenA;
    private String tokenB;

    @BeforeEach
    void setUp() throws Exception {
        store = new AuthTestSupport.Store();
        FolixAuthSettings settings = AuthTestSupport.settings();
        FolixAuthService authService = new FolixAuthService(store.farmerRepo, store.sessionRepo,
                AuthTestSupport.encoder(), settings);
        FolixSessionCookies cookies = new FolixSessionCookies(settings);

        FolixFarmerAgriProfileRepository profileRepo = inMemoryProfileRepository();
        FolixSupportContactRepository contactRepo = inMemoryContactRepository();

        mvc = MockMvcBuilders
                .standaloneSetup(new FolixAuthController(authService, cookies), new FolixFarmerController(),
                        new FolixAgriNetworkController(new FolixAgriNetworkService(profileRepo, contactRepo)))
                .setControllerAdvice(new FolixAuthExceptionHandler(), new FolixAgriNetworkExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticatedFarmerArgumentResolver())
                .addPlaceholderValue("cropscanner.cors.allowed-origin", AuthTestSupport.ORIGIN)
                .addMappedInterceptors(FolixWebConfig.IDENTITY_PATHS,
                        new FolixAuthInterceptor(authService, cookies, settings, false))
                .addMappedInterceptors(FolixWebConfig.PROTECTED_PATHS,
                        new FolixAuthInterceptor(authService, cookies, settings, true))
                .build();

        register("Asha Nair", "asha@example.com");
        register("Bala Menon", "bala@example.com");
        tokenA = login("asha@example.com");
        tokenB = login("bala@example.com");
    }

    // ------------------------------------------------------------------ in-memory stand-ins

    private FolixFarmerAgriProfileRepository inMemoryProfileRepository() {
        FolixFarmerAgriProfileRepository repo = Mockito.mock(FolixFarmerAgriProfileRepository.class);
        when(repo.findById(any(UUID.class)))
                .thenAnswer(inv -> Optional.ofNullable(profiles.get(inv.<UUID>getArgument(0))));
        when(repo.save(any(FolixFarmerAgriProfileEntity.class))).thenAnswer(inv -> {
            FolixFarmerAgriProfileEntity e = inv.getArgument(0);
            profiles.put(e.getFarmerId(), e);
            return e;
        });
        return repo;
    }

    private FolixSupportContactRepository inMemoryContactRepository() {
        FolixSupportContactRepository repo = Mockito.mock(FolixSupportContactRepository.class);
        when(repo.findByActiveTrueOrderByNameAsc()).thenAnswer(inv ->
                contacts.stream().filter(FolixSupportContactEntity::isActive)
                        .sorted((a, b) -> a.getName().compareTo(b.getName())).toList());
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

    // ------------------------------------------------------------------ 1. authentication / ownership (test 1-4)

    @Test
    void profileEndpointsRequireASessionAndStoreNothingWithoutOne() throws Exception {
        mvc.perform(get("/api/farmer/agri-network/profile")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mvc.perform(put("/api/farmer/agri-network/profile").contentType("application/json")
                        .content("{\"villageOrArea\":\"Somewhere\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/farmer/agri-network/profile").cookie(session("not-a-real-token"))
                        .contentType("application/json").content("{\"villageOrArea\":\"Somewhere\"}"))
                .andExpect(status().isUnauthorized());
        assertTrue(profiles.isEmpty(), "no profile may be stored without an authenticated farmer");
    }

    @Test
    void authenticatedFarmerCanRetrieveTheirOwnAgriculturalProfile() throws Exception {
        mvc.perform(get("/api/farmer/agri-network/profile").cookie(session(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasProfile").value(false));
    }

    @Test
    void authenticatedFarmerCanUpdateTheirOwnAgriculturalInformation() throws Exception {
        String body = "{\"villageOrArea\":\"Kaipamangalam\",\"crops\":\"Rice, banana\","
                + "\"preferredCategory\":\"CROP_DISEASE_ASSISTANCE\"}";
        mvc.perform(put("/api/farmer/agri-network/profile").cookie(session(tokenA))
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasProfile").value(true))
                .andExpect(jsonPath("$.villageOrArea").value("Kaipamangalam"))
                .andExpect(jsonPath("$.crops").value("Rice, banana"))
                .andExpect(jsonPath("$.preferredCategory").value("CROP_DISEASE_ASSISTANCE"));

        assertTrue(profiles.containsKey(idOf("asha@example.com")));
    }

    @Test
    void aFarmerIdSentInTheBodyCannotUpdateSomeoneElsesProfile() throws Exception {
        UUID bala = idOf("bala@example.com");
        String body = "{\"villageOrArea\":\"Ashas village\",\"farmerId\":\"" + bala + "\",\"farmer_id\":\"" + bala + "\"}";

        mvc.perform(put("/api/farmer/agri-network/profile").cookie(session(tokenA))
                        .contentType("application/json").content(body))
                .andExpect(status().isOk());

        assertEquals(1, profiles.size());
        assertTrue(profiles.containsKey(idOf("asha@example.com")), "stored for the session's farmer (A) only");
        assertFalse(profiles.containsKey(bala), "B's profile was NOT touched by A's request");
        mvc.perform(get("/api/farmer/agri-network/profile").cookie(session(tokenB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasProfile").value(false));
    }

    @Test
    void aForeignOriginCannotUpdateTheProfile() throws Exception {
        mvc.perform(put("/api/farmer/agri-network/profile").cookie(session(tokenA))
                        .header("Origin", "https://evil.example")
                        .contentType("application/json").content("{\"villageOrArea\":\"X\"}"))
                .andExpect(status().isForbidden());
        assertTrue(profiles.isEmpty());
    }

    // ------------------------------------------------------------------ 2. validation

    @Test
    void blankFieldsAreAcceptedAndClearTheProfileField() throws Exception {
        mvc.perform(put("/api/farmer/agri-network/profile").cookie(session(tokenA))
                        .contentType("application/json").content("{\"villageOrArea\":\"  \",\"crops\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.villageOrArea").doesNotExist())
                .andExpect(jsonPath("$.crops").doesNotExist());
    }

    @Test
    void overlyLongFieldsAreRejectedWithFourHundred() throws Exception {
        String tooLong = "x".repeat(151);
        mvc.perform(put("/api/farmer/agri-network/profile").cookie(session(tokenA))
                        .contentType("application/json").content("{\"villageOrArea\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest());
        assertFalse(profiles.containsKey(idOf("asha@example.com")));
    }

    // ------------------------------------------------------------------ 3. directory

    @Test
    void supportDirectoryReturnsConfiguredActiveResourcesOnly() throws Exception {
        contacts.add(new FolixSupportContactEntity("Demo Krishi Bhavan", FolixSupportCategory.KRISHI_BHAVAN,
                "FOLIX Demo Directory", "Sample locality", null, null, null, true, true));
        contacts.add(new FolixSupportContactEntity("Inactive Old Contact", FolixSupportCategory.GENERAL_SUPPORT,
                "Org", "Locality", "555-0000", "old@example.com", "123 Street", false, true));

        mvc.perform(get("/api/farmer/agri-network/directory").cookie(session(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Demo Krishi Bhavan"))
                .andExpect(jsonPath("$[0].phone").doesNotExist())
                .andExpect(jsonPath("$[0].email").doesNotExist())
                .andExpect(jsonPath("$[0].address").doesNotExist());
    }

    @Test
    void inactiveResourcesAreExcludedFromTheDirectory() throws Exception {
        contacts.add(new FolixSupportContactEntity("Inactive Contact", FolixSupportCategory.GENERAL_SUPPORT,
                "Org", "Locality", "555-1111", "hidden@example.com", "Secret address", false, true));

        String body = mvc.perform(get("/api/farmer/agri-network/directory").cookie(session(tokenA)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertEquals("[]", body);
        assertFalse(body.contains("hidden@example.com"), "an inactive/private field must never be returned");
    }

    @Test
    void anEmptyDirectoryIsAnEmptyArrayNotAnError() throws Exception {
        mvc.perform(get("/api/farmer/agri-network/directory").cookie(session(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void directoryAlsoRequiresASession() throws Exception {
        mvc.perform(get("/api/farmer/agri-network/directory")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ 4. existing auth remains intact

    @Test
    void existingAuthenticationAndBasicProfileAreUntouchedByStage6D() throws Exception {
        mvc.perform(get("/api/farmer/profile").cookie(session(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("asha@example.com"));
        mvc.perform(get("/api/farmer/profile")).andExpect(status().isUnauthorized());
    }
}
