package com.cropscanner.folix.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * /api/auth — farmer registration, login, logout and session status.
 *
 * A thin HTTP wrapper: all rules live in {@link FolixAuthService}. CORS is credentialed
 * ({@code allowCredentials}) for the single configured frontend origin — never a wildcard — so the
 * browser will store and send the HttpOnly session cookie cross-origin. Responses are never cached.
 * Failures use the standard FOLIX error envelope via {@link FolixAuthExceptionHandler}.
 */
@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "${cropscanner.cors.allowed-origin:http://localhost:5173}", allowCredentials = "true")
public class FolixAuthController {

    private final FolixAuthService authService;
    private final FolixSessionCookies cookies;

    public FolixAuthController(FolixAuthService authService, FolixSessionCookies cookies) {
        this.authService = authService;
        this.cookies = cookies;
    }

    /** Creates an account. Does NOT log the farmer in — the client calls {@code /login} next. */
    @PostMapping("/register")
    public ResponseEntity<FarmerProfileResponse> register(@RequestBody(required = false) RegisterRequest request) {
        FarmerProfileResponse created = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(created);
    }

    /** Verifies credentials and starts a session: the session token travels only in the HttpOnly Set-Cookie header, never in the body. */
    @PostMapping("/login")
    public ResponseEntity<FarmerProfileResponse> login(@RequestBody(required = false) LoginRequest request,
                                                       HttpServletRequest http) {
        Optional<String> previousToken = cookies.read(http);
        FolixAuthService.LoginResult result = authService.login(request);
        // A fresh login replaces any session this browser already had.
        previousToken.ifPresent(authService::logout);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, cookies.issue(result.sessionToken()).toString())
                .body(FarmerProfileResponse.from(result.farmer()));
    }

    /** Revokes the current session (if any) and clears the cookie. Always 204, so it is safe to call twice. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest http) {
        cookies.read(http).ifPresent(authService::logout);
        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, cookies.clear().toString())
                .build();
    }

    /** "Am I logged in?" — always 200, so pages can check without a 401 showing in the browser console. */
    @GetMapping("/session")
    public ResponseEntity<SessionStatusResponse> session(HttpServletRequest http) {
        SessionStatusResponse body = FolixAuthInterceptor.currentFarmer(http)
                .map(SessionStatusResponse::of)
                .orElseGet(SessionStatusResponse::anonymous);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
