package com.cropscanner.folix;

import com.cropscanner.service.impl.GeminiKeyPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Optional;

/**
 * Wires the FOLIX Scanner's Gemini client, and its optional second-tier
 * online Pl@ntNet fallback (see {@link FolixScanService}).
 *
 * Key handling — read this before changing it:
 * The legacy {@code GeminiKeyPool} already knows how to load keys from
 * {@code GEMINI_API_KEYS} / {@code GEMINI_KEY_1..20}, and that loading is reused
 * so there is one place that understands those variables. Its ROTATION and
 * COOLDOWN behaviour, however, is deliberately NOT used here:
 *
 *  - Google applies Gemini quotas per PROJECT, not per API key, so extra keys in
 *    one project add no capacity, and spreading load across keys/projects to get
 *    around a 429 is quota circumvention, which the Scanner must not do.
 *  - The Scanner therefore uses the FIRST configured key for every request, makes
 *    ONE request per scan, and reacts to a 429 by reporting "busy" to the user.
 *    It never selects another key, waits-and-retries, or switches model.
 */
@Configuration
public class FolixScannerConfig {

    private static final Logger log = LoggerFactory.getLogger(FolixScannerConfig.class);

    @Bean
    public GeminiSettings folixGeminiSettings(
            @Value("${folix.gemini.base-url:https://generativelanguage.googleapis.com/v1beta}") String baseUrl,
            @Value("${folix.gemini.model:gemini-3.5-flash-lite}") String model,
            @Value("${folix.gemini.timeout-seconds:45}") long timeoutSeconds,
            @Value("${folix.gemini.connect-timeout-seconds:10}") long connectTimeoutSeconds,
            @Value("${folix.gemini.max-output-tokens:4096}") int maxOutputTokens,
            @Value("${folix.gemini.thinking-level:}") String thinkingLevel) {
        return new GeminiSettings(baseUrl, model, Duration.ofSeconds(timeoutSeconds),
                Duration.ofSeconds(connectTimeoutSeconds), maxOutputTokens, thinkingLevel);
    }

    @Bean
    public ApiKeySource folixGeminiApiKeySource(GeminiKeyPool pool) {
        if (!pool.hasAnyKeyConfigured()) {
            log.warn("FOLIX Scanner: no Gemini API key configured (GEMINI_API_KEYS / GEMINI_KEY_1). "
                    + "POST /api/scan/analyze will return ANALYSIS_UNAVAILABLE until one is set.");
        } else if (pool.configuredKeyCount() > 1) {
            log.info("FOLIX Scanner: {} Gemini keys are configured; the Scanner uses only the first. "
                    + "(Quotas are per Google project, so additional keys would not add capacity.)",
                    pool.configuredKeyCount());
        }
        return pool::firstConfiguredKey;
    }

    @Bean
    public VisionClient folixVisionClient(GeminiSettings settings,
                                           @Qualifier("folixGeminiApiKeySource") ApiKeySource keys) {
        return new GeminiRestVisionClient(settings, keys);
    }

    // ------------------------------------------------------------------
    // Pl@ntNet — second-tier ONLINE fallback, used only when Gemini itself
    // fails (see FolixScanService). Wired as an Optional<PlantNetSpeciesClient>
    // dependency so the Scanner works exactly as before when PLANTNET_API_KEY
    // is unset: no bean is produced, and FolixScanService falls back to
    // rethrowing the original Gemini failure unchanged.
    // ------------------------------------------------------------------

    @Bean
    public PlantNetSettings folixPlantNetSettings(
            @Value("${folix.plantnet.base-url:https://my-api.plantnet.org}") String baseUrl,
            @Value("${folix.plantnet.timeout-seconds:20}") long timeoutSeconds,
            @Value("${folix.plantnet.connect-timeout-seconds:10}") long connectTimeoutSeconds,
            @Value("${folix.plantnet.organs:auto}") String organs,
            @Value("${folix.plantnet.lang:en}") String lang) {
        return new PlantNetSettings(baseUrl, Duration.ofSeconds(timeoutSeconds),
                Duration.ofSeconds(connectTimeoutSeconds), organs, lang);
    }

    @Bean
    public ApiKeySource folixPlantNetApiKeySource(
            @Value("${PLANTNET_API_KEY:${folix.plantnet.api-key:}}") String apiKey) {
        boolean present = apiKey != null && !apiKey.isBlank();
        if (!present) {
            log.info("FOLIX Scanner: no Pl@ntNet API key configured (PLANTNET_API_KEY). "
                    + "The Gemini->Pl@ntNet fallback is disabled; a failed Gemini call surfaces unchanged.");
        }
        String key = present ? apiKey.trim() : null;
        return () -> Optional.ofNullable(key);
    }

    /**
     * Optional bean: absent when {@code folixPlantNetApiKeySource} finds no key, so
     * {@link FolixScanService}'s {@code Optional<PlantNetSpeciesClient>} constructor
     * parameter resolves to {@code Optional.empty()} and the fallback is skipped cleanly.
     */
    @Bean
    public PlantNetSpeciesClient folixPlantNetSpeciesClient(
            PlantNetSettings settings,
            @Qualifier("folixPlantNetApiKeySource") ApiKeySource plantNetKeys) {
        if (plantNetKeys.get().isEmpty()) {
            return null;
        }
        return new PlantNetSpeciesClient(settings, plantNetKeys);
    }
}
