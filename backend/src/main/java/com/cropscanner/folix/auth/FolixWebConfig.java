package com.cropscanner.folix.auth;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Decides which routes are account-aware.
 *
 * <ul>
 *   <li>{@link #IDENTITY_PATHS} — {@code /api/auth/**}: register/login/logout/session are public, but
 *       state-changing calls still get the Origin check and the current farmer (if any) is resolved.</li>
 *   <li>{@link #PROTECTED_PATHS} — a valid farmer session is REQUIRED (401 otherwise): the farmer
 *       profile and the saved-report endpoints the Dashboard and Save Report use.</li>
 * </ul>
 * Everything else — the Scanner's {@code /api/scan/analyze}, the legacy {@code /api/v1/**} CropGuard
 * API and the authority portal endpoints — is not touched by this configuration and behaves exactly
 * as it did before Stage 6A.
 */
@Configuration
public class FolixWebConfig implements WebMvcConfigurer {

    public static final String[] IDENTITY_PATHS = {"/api/auth/**"};

    public static final String[] PROTECTED_PATHS = {
            "/api/farmer/**",
            "/api/scan/reports",
            "/api/scan/reports/**"
    };

    private final FolixAuthService authService;
    private final FolixSessionCookies cookies;
    private final FolixAuthSettings settings;

    public FolixWebConfig(FolixAuthService authService, FolixSessionCookies cookies, FolixAuthSettings settings) {
        this.authService = authService;
        this.cookies = cookies;
        this.settings = settings;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new FolixAuthInterceptor(authService, cookies, settings, false))
                .addPathPatterns(IDENTITY_PATHS);
        registry.addInterceptor(new FolixAuthInterceptor(authService, cookies, settings, true))
                .addPathPatterns(PROTECTED_PATHS);
    }

    @Override
    public void addCorsMappings(org.springframework.web.servlet.config.annotation.CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*", "http://10.*:*", "http://172.*:*", "http://192.168.*:*")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new AuthenticatedFarmerArgumentResolver());
    }
}
