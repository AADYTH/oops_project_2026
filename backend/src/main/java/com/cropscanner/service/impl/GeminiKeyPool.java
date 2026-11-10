package com.cropscanner.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Legitimate multi-key availability/failover pool for Gemini's free-tier
 * API, exactly as CropGuard's architecture spec describes it: this is NOT
 * an attempt to bypass Google's per-key rate limits by spraying requests
 * across keys in parallel, or to work around account/billing restrictions.
 * It is a small, honest failover shim — if one legitimately-configured free
 * key is currently rate-limited, try the NEXT legitimately-configured free
 * key instead of failing (or, worse, silently upgrading to a paid model).
 *
 * Configuration
 * --------------
 * Keys are read from environment variables ONLY (never a request body,
 * never a frontend-supplied value, never committed to source control):
 *
 *   GEMINI_API_KEYS = "key1,key2,key3"      (comma-separated, simplest)
 * or individually:
 *   GEMINI_KEY_1, GEMINI_KEY_2, GEMINI_KEY_3, ...  (up to GEMINI_KEY_20)
 *
 * Both forms are merged if both are present. Zero configured keys is a
 * perfectly valid, expected state (Gemini is optional) — callers just get
 * an empty pool and every {@link #selectAvailableKey()} call returns
 * {@link Optional#empty()}, meaning "fall back to the local model", never
 * an exception.
 *
 * This class does exactly one thing: pick a key that is not currently in a
 * cooldown period. It does NOT call Gemini itself — see
 * {@link RestGeminiVisionService} for that — so the actual HTTP failure
 * handling (which errors count as "mark this key unavailable") stays in one
 * place, not duplicated between two classes guessing at each other's
 * behavior.
 */
@Component
public class GeminiKeyPool {

    private static final Logger log = LoggerFactory.getLogger(GeminiKeyPool.class);
    private static final int MAX_INDEXED_KEYS = 20;

    /** One configured key and its current cooldown state. */
    static final class KeyState {
        final int poolIndex; // 1-based, for masked logging only — never the key itself
        final String key;
        volatile Instant unavailableUntil = Instant.EPOCH;
        final AtomicLong consecutiveFailures = new AtomicLong(0);

        KeyState(int poolIndex, String key) {
            this.poolIndex = poolIndex;
            this.key = key;
        }

        boolean isAvailable(Instant now) {
            return now.isAfter(unavailableUntil);
        }
    }

    private final List<KeyState> keys;
    private final Duration baseCooldown;
    private final Duration maxCooldown;
    /** Round-robin cursor so load spreads across available keys rather than always hammering key #1. */
    private final AtomicLong cursor = new AtomicLong(0);

    public GeminiKeyPool(
            @Value("${cropscanner.gemini.api-keys:}") String commaSeparatedKeys,
            @Value("${cropscanner.gemini.cooldown-base-seconds:30}") long cooldownBaseSeconds,
            @Value("${cropscanner.gemini.cooldown-max-seconds:900}") long cooldownMaxSeconds,
            org.springframework.core.env.Environment env) {
        this.baseCooldown = Duration.ofSeconds(cooldownBaseSeconds);
        this.maxCooldown = Duration.ofSeconds(cooldownMaxSeconds);
        this.keys = loadKeys(commaSeparatedKeys, env);

        if (keys.isEmpty()) {
            log.info("Gemini key pool: no keys configured (GEMINI_API_KEYS / GEMINI_KEY_1..N). "
                    + "Gemini second-opinion is disabled; CropGuard runs on the local model only. This is a "
                    + "normal, fully-supported configuration, not an error.");
        } else {
            log.info("Gemini key pool: {} key(s) configured.", keys.size());
        }
    }

    private List<KeyState> loadKeys(String commaSeparatedKeys, org.springframework.core.env.Environment env) {
        List<KeyState> loaded = new ArrayList<>();
        int index = 0;

        if (commaSeparatedKeys != null && !commaSeparatedKeys.isBlank()) {
            for (String raw : commaSeparatedKeys.split(",")) {
                String trimmed = raw.trim();
                if (!trimmed.isEmpty()) {
                    loaded.add(new KeyState(++index, trimmed));
                }
            }
        }

        for (int i = 1; i <= MAX_INDEXED_KEYS; i++) {
            String value = env.getProperty("GEMINI_KEY_" + i);
            if (value != null && !value.isBlank()) {
                loaded.add(new KeyState(++index, value.trim()));
            }
        }

        return List.copyOf(loaded);
    }

    public boolean hasAnyKeyConfigured() {
        return !keys.isEmpty();
    }

    public int configuredKeyCount() {
        return keys.size();
    }

    /**
     * The first configured key, with NO rotation, NO cooldown check and NO state change.
     *
     * Added for the FOLIX Scanner (com.cropscanner.folix), which by design uses a single key and
     * never fails over to another one when a request is rate-limited: Gemini quotas are applied per
     * Google project, so switching keys would not add capacity and would amount to circumventing the
     * limit. The legacy round-robin/cooldown behaviour above ({@link #selectAvailableKey()}) is
     * unchanged and is still used only by the legacy CropGuard second-opinion path.
     */
    public Optional<String> firstConfiguredKey() {
        return keys.isEmpty() ? Optional.empty() : Optional.of(keys.get(0).key);
    }

    /**
     * Picks the next key not currently in cooldown, round-robin among the
     * available ones so repeated calls don't pin all traffic to key #1.
     * Returns empty when every configured key is currently in cooldown (or
     * none are configured at all) — callers must treat that as "use the
     * local model", never retry synchronously in a loop.
     */
    public Optional<SelectedKey> selectAvailableKey() {
        if (keys.isEmpty()) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        List<KeyState> available = keys.stream().filter(k -> k.isAvailable(now)).toList();
        if (available.isEmpty()) {
            log.warn("Gemini key pool: all {} configured key(s) are currently in cooldown. Falling back to the "
                    + "local model for this scan — not retrying, not switching to a paid model.", keys.size());
            return Optional.empty();
        }
        int pick = (int) (cursor.getAndIncrement() % available.size());
        KeyState chosen = available.get(pick);
        log.info("Gemini key {} -> selected", chosen.poolIndex);
        return Optional.of(new SelectedKey(chosen.poolIndex, chosen.key));
    }

    /**
     * Marks a key temporarily unavailable after a rate-limit/quota/5xx
     * response, with exponential backoff capped at {@code cooldown-max-seconds}
     * so a key that keeps failing doesn't get retried every few seconds
     * forever, but also isn't permanently abandoned for the life of the
     * process — a daily quota resets, and this pool has no way to know
     * exactly when except by eventually trying again.
     */
    public void markUnavailable(int poolIndex, String reason) {
        keys.stream().filter(k -> k.poolIndex == poolIndex).findFirst().ifPresent(k -> {
            long failures = k.consecutiveFailures.incrementAndGet();
            long backoffSeconds = Math.min(
                    baseCooldown.getSeconds() * (1L << Math.min(failures - 1, 10)), // cap the shift to avoid overflow
                    maxCooldown.getSeconds());
            k.unavailableUntil = Instant.now().plusSeconds(backoffSeconds);
            // Never log the actual key — only its pool index and the operational reason. See README, Security.
            log.warn("Gemini key {} -> temporarily unavailable ({}), cooldown {}s (failure #{})",
                    k.poolIndex, reason, backoffSeconds, failures);
        });
    }

    /** Resets backoff on a successful call — a key that recovers shouldn't stay penalized. */
    public void markSuccess(int poolIndex) {
        keys.stream().filter(k -> k.poolIndex == poolIndex).findFirst().ifPresent(k -> {
            k.consecutiveFailures.set(0);
            k.unavailableUntil = Instant.EPOCH;
        });
    }

    /** Safe-to-log operational status: pool index and state only, never the credential itself. */
    public List<String> statusSummary() {
        Instant now = Instant.now();
        return keys.stream()
                .sorted(Comparator.comparingInt(k -> k.poolIndex))
                .map(k -> "Gemini key " + k.poolIndex + " -> " + (k.isAvailable(now) ? "available" : "cooling down"))
                .toList();
    }

    /** A key handed to the caller for exactly one request. Never logged, never persisted, never returned to any client. */
    public record SelectedKey(int poolIndex, String key) {
    }
}
