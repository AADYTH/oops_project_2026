package com.cropscanner.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

/**
 * Addresses the "no auth, rate limiting, or abuse protection on the API"
 * gap called out in the honest-downsides review. Deliberately implemented
 * with plain JDK concurrency primitives (no new Maven dependency, since
 * this environment can't fetch one) rather than a library like Bucket4j —
 * swap this for Bucket4j + Redis if you need rate limiting that's shared
 * across multiple backend instances; this implementation is per-instance
 * only.
 *
 * Two independent protections, both configurable via application.yml:
 *
 * 1. API key ({@code cropscanner.security.require-api-key: true} +
 *    {@code cropscanner.security.api-keys: key1,key2}). OFF by default so
 *    the app keeps working out of the box for a public farmer-facing
 *    client; turn it on once you have a mobile app / web client you can
 *    embed a key into, to keep the scan endpoint from being hit directly
 *    by anyone who finds the URL.
 *
 * 2. Per-client-IP rate limiting (always on), a fixed-window counter capped
 *    at {@code cropscanner.security.rate-limit-per-minute} (default 30)
 *    requests/minute. Returns HTTP 429 with a Retry-After-style message
 *    when exceeded. This alone stops the most common abuse pattern
 *    (someone scripting requests against a free inference backend) even
 *    with no API key configured.
 */
@Component
@Order(1)
public class ApiProtectionFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiProtectionFilter.class);

    private final boolean requireApiKey;
    private final Set<String> validApiKeys;
    private final int rateLimitPerMinute;

    // clientKey -> timestamps (ms) of requests within the current window
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>> requestLog = new ConcurrentHashMap<>();

    public ApiProtectionFilter(
            @Value("${cropscanner.security.require-api-key:false}") boolean requireApiKey,
            @Value("${cropscanner.security.api-keys:}") String apiKeysCsv,
            @Value("${cropscanner.security.rate-limit-per-minute:30}") int rateLimitPerMinute) {
        this.requireApiKey = requireApiKey;
        this.validApiKeys = Arrays.stream(apiKeysCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        this.rateLimitPerMinute = rateLimitPerMinute;
        if (requireApiKey && validApiKeys.isEmpty()) {
            log.warn("cropscanner.security.require-api-key=true but no keys are configured in " +
                    "cropscanner.security.api-keys — every request will be rejected until keys are added.");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        // Health checks and the H2 console are exempt so uptime monitors and local dev aren't rate-limited.
        if (path.endsWith("/health") || path.startsWith("/h2-console")) {
            chain.doFilter(request, response);
            return;
        }

        String clientKey = clientIdentifier(request);

        if (isRateLimited(clientKey)) {
            log.warn("Rate limit exceeded for {} on {}", clientKey, path);
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"errorCode\":\"RATE_LIMITED\",\"message\":\"Too many requests. Please wait a minute and try again.\"}");
            return;
        }

        if (requireApiKey) {
            String providedKey = request.getHeader("X-Api-Key");
            if (providedKey == null || !validApiKeys.contains(providedKey)) {
                response.setStatus(401);
                response.setContentType("application/json");
                response.getWriter().write(
                        "{\"errorCode\":\"UNAUTHORIZED\",\"message\":\"A valid X-Api-Key header is required.\"}");
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private String clientIdentifier(HttpServletRequest request) {
        String apiKey = request.getHeader("X-Api-Key");
        if (apiKey != null && !apiKey.isBlank()) {
            return "key:" + apiKey;
        }
        String forwardedFor = request.getHeader("X-Forwarded-For");
        String ip = (forwardedFor != null && !forwardedFor.isBlank())
                ? forwardedFor.split(",")[0].trim()
                : request.getRemoteAddr();
        return "ip:" + ip;
    }

    private boolean isRateLimited(String clientKey) {
        long now = Instant.now().toEpochMilli();
        long windowStart = now - 60_000L;

        ConcurrentLinkedDeque<Long> timestamps = requestLog.computeIfAbsent(clientKey, k -> new ConcurrentLinkedDeque<>());
        timestamps.add(now);

        // Evict anything outside the trailing 60-second window.
        Long oldest;
        while ((oldest = timestamps.peekFirst()) != null && oldest < windowStart) {
            timestamps.pollFirst();
        }

        boolean limited = timestamps.size() > rateLimitPerMinute;

        // Cheap unbounded-growth guard: occasionally sweep out clients that have
        // gone fully idle so requestLog doesn't grow forever under long uptime.
        // Not perfectly precise (best-effort, no lock across the whole map) but
        // good enough since a single-instance in-memory limiter is already a
        // stated limitation (see class Javadoc re: Bucket4j/Redis for scale-out).
        if (requestLog.size() > 50_000 && Math.random() < 0.01) {
            requestLog.entrySet().removeIf(e -> {
                Long last = e.getValue().peekLast();
                return last == null || last < windowStart;
            });
        }

        return limited;
    }
}
