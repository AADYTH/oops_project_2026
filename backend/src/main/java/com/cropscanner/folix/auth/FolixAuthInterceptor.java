package com.cropscanner.folix.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Server-side authentication for the account-aware routes (registered in {@link FolixWebConfig}).
 *
 * Per request it:
 * <ol>
 *   <li>lets CORS pre-flights straight through (they carry no cookie and must never get a 401);</li>
 *   <li>for state-changing methods (POST/PUT/PATCH/DELETE) checks the browser's {@code Origin}
 *       header against the configured frontend origin — the CSRF defence that goes with a cookie
 *       session. A request with no Origin header (curl, server-to-server) is allowed: it cannot be a
 *       forged browser request riding a victim's cookie;</li>
 *   <li>resolves the session cookie via {@link FolixAuthService#authenticate(String)} and, on
 *       success, stores the {@link AuthenticatedFarmer} as a request attribute;</li>
 *   <li>when {@code requireAuthentication} is set, answers 401 without ever reaching the controller
 *       if there is no valid session.</li>
 * </ol>
 * It is a Spring MVC interceptor (not a servlet filter) on purpose: MVC's CORS handling runs first,
 * so even the 401/403 responses below carry the CORS headers the browser needs to show the error to
 * the page instead of a generic "network error".
 *
 * The identity is never taken from a request parameter, header or body — only from the cookie's
 * server-side session.
 */
public class FolixAuthInterceptor implements HandlerInterceptor {

    /** Request attribute holding the {@link AuthenticatedFarmer}. Use {@link #currentFarmer(HttpServletRequest)}. */
    public static final String FARMER_ATTRIBUTE = FolixAuthInterceptor.class.getName() + ".FARMER";

    private final FolixAuthService authService;
    private final FolixSessionCookies cookies;
    private final FolixAuthSettings settings;
    private final boolean requireAuthentication;

    public FolixAuthInterceptor(FolixAuthService authService, FolixSessionCookies cookies,
                                FolixAuthSettings settings, boolean requireAuthentication) {
        this.authService = authService;
        this.cookies = cookies;
        this.settings = settings;
        this.requireAuthentication = requireAuthentication;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (CorsUtils.isPreFlightRequest(request)) {
            return true;
        }

        if (isStateChanging(request.getMethod()) && !originAllowed(request)) {
            writeError(response, FolixAuthException.Code.ORIGIN_NOT_ALLOWED,
                    FolixAuthException.originNotAllowed().getMessage());
            return false;
        }

        Optional<String> token = cookies.read(request);
        Optional<AuthenticatedFarmer> farmer = token.flatMap(authService::authenticate);
        if (farmer.isPresent()) {
            request.setAttribute(FARMER_ATTRIBUTE, farmer.get());
            return true;
        }

        if (requireAuthentication) {
            if (token.isPresent()) {
                // A stale/invalid cookie: tell the browser to drop it.
                response.addHeader(HttpHeaders.SET_COOKIE, cookies.clear().toString());
            }
            writeError(response, FolixAuthException.Code.UNAUTHENTICATED,
                    FolixAuthException.unauthenticated().getMessage());
            return false;
        }
        return true;
    }

    /** The farmer authenticated for this request, if any. */
    public static Optional<AuthenticatedFarmer> currentFarmer(HttpServletRequest request) {
        Object value = request.getAttribute(FARMER_ATTRIBUTE);
        return value instanceof AuthenticatedFarmer f ? Optional.of(f) : Optional.empty();
    }

    private boolean originAllowed(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin == null) {
            return true;
        }
        String normalized = FolixAuthSettings.normalizeOrigin(origin);
        if (settings.allowedOrigins().contains(normalized)) {
            return true;
        }
        // Also allow local development / LAN origins (phone on same Wi-Fi or mobile hotspot)
        return normalized.matches("^https?://(localhost|127\\.0\\.0\\.1|192\\.168\\.\\d+\\.\\d+|172\\.(1[6-9]|2\\d|3[0-1])\\.\\d+\\.\\d+|10\\.\\d+\\.\\d+\\.\\d+)(:\\d+)?$");
    }

    private static boolean isStateChanging(String method) {
        return "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method);
    }

    /** Same {@code {"error": {code, message, retryable}}} envelope as every other FOLIX error. Messages are fixed strings (no user input), so no escaping is needed. */
    private static void writeError(HttpServletResponse response, FolixAuthException.Code code, String message)
            throws IOException {
        response.setStatus(code.httpStatus());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.getWriter().write("{\"error\":{\"code\":\"" + code.name() + "\",\"message\":\""
                + message + "\",\"retryable\":false}}");
    }
}
