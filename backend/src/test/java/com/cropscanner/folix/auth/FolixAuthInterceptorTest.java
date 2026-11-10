package com.cropscanner.folix.auth;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct tests of {@link FolixAuthInterceptor} — the cases that are awkward through MockMvc because
 * MVC's own CORS processing answers a foreign Origin first: CORS pre-flights, the CSRF Origin check,
 * and cookie hygiene.
 */
class FolixAuthInterceptorTest {

    private FolixAuthSettings settings;
    private FolixAuthService service;
    private FolixSessionCookies cookies;
    private FolixAuthInterceptor protect;
    private FolixAuthInterceptor identify;
    private String token;

    @BeforeEach
    void setUp() {
        AuthTestSupport.Store store = new AuthTestSupport.Store();
        settings = AuthTestSupport.settings();
        service = new FolixAuthService(store.farmerRepo, store.sessionRepo, AuthTestSupport.encoder(), settings);
        cookies = new FolixSessionCookies(settings);
        protect = new FolixAuthInterceptor(service, cookies, settings, true);
        identify = new FolixAuthInterceptor(service, cookies, settings, false);
        service.register(new RegisterRequest("Asha", "asha@example.com", "correct horse 42"));
        token = service.login(new LoginRequest("asha@example.com", "correct horse 42")).sessionToken();
    }

    private MockHttpServletRequest request(String method, String origin, String sessionToken) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, "/api/farmer/profile");
        if (origin != null) r.addHeader("Origin", origin);
        if (sessionToken != null) r.setCookies(new Cookie("FOLIX_SESSION", sessionToken));
        return r;
    }

    @Test
    void corsPreflightPassesThroughWithoutAuthentication() throws Exception {
        MockHttpServletRequest preflight = request("OPTIONS", AuthTestSupport.ORIGIN, null);
        preflight.addHeader("Access-Control-Request-Method", "GET");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(protect.preHandle(preflight, response, new Object()));
        assertEquals(200, response.getStatus());
    }

    @Test
    void aStateChangingRequestFromAForeignOriginIsRefusedEvenWithAValidCookie() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(protect.preHandle(request("POST", "https://evil.example", token), response, new Object()));
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("ORIGIN_NOT_ALLOWED"));
    }

    @Test
    void thePublicAuthEndpointsGetTheSameOriginCheckSoLoginCannotBeForged() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(identify.preHandle(request("POST", "https://evil.example", null), response, new Object()));
        assertEquals(403, response.getStatus());
        assertFalse(identify.preHandle(request("POST", "null", null), new MockHttpServletResponse(), new Object()));
    }

    @Test
    void theConfiguredOriginAndNonBrowserClientsAreAllowed() throws Exception {
        assertTrue(protect.preHandle(request("POST", AuthTestSupport.ORIGIN, token), new MockHttpServletResponse(), new Object()));
        assertTrue(protect.preHandle(request("POST", "HTTP://LOCALHOST:5173/", token), new MockHttpServletResponse(), new Object()));
        assertTrue(protect.preHandle(request("POST", null, token), new MockHttpServletResponse(), new Object()));
    }

    @Test
    void readsAreNotSubjectToTheOriginCheck() throws Exception {
        assertTrue(identify.preHandle(request("GET", "https://evil.example", null), new MockHttpServletResponse(), new Object()));
    }

    @Test
    void aValidSessionPutsTheFarmerOnTheRequest() throws Exception {
        MockHttpServletRequest request = request("GET", null, token);

        assertTrue(protect.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertEquals("asha@example.com", FolixAuthInterceptor.currentFarmer(request).orElseThrow().email());
    }

    @Test
    void aStaleCookieGets401AndIsClearedFromTheBrowser() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(protect.preHandle(request("GET", null, "B".repeat(43)), response, new Object()));
        assertEquals(401, response.getStatus());
        assertTrue(response.getHeader("Set-Cookie").contains("Max-Age=0"));
    }

    @Test
    void theOptionalInterceptorLetsAnonymousRequestsThroughWithoutAFarmer() throws Exception {
        MockHttpServletRequest request = request("GET", null, null);

        assertTrue(identify.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertTrue(FolixAuthInterceptor.currentFarmer(request).isEmpty());
    }

    @Test
    void theCookieCarriesTheSecurityAttributes() {
        String issued = cookies.issue("T".repeat(43)).toString();
        assertTrue(issued.contains("HttpOnly"));
        assertTrue(issued.contains("SameSite=Lax"));
        assertTrue(issued.contains("Path=/api"));
        assertFalse(issued.contains("Secure"), "Secure is off in the local-development settings");

        FolixAuthSettings https = new FolixAuthSettings(settings.sessionTtl(), "FOLIX_SESSION", true, "Lax", settings.allowedOrigins());
        assertTrue(new FolixSessionCookies(https).issue("T".repeat(43)).toString().contains("Secure"));
    }
}
