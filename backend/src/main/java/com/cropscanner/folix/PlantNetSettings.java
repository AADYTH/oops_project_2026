package com.cropscanner.folix;

import java.time.Duration;

/**
 * Non-secret Pl@ntNet call settings. Mirrors {@link GeminiSettings}: the API
 * key is intentionally NOT here; it comes from a dedicated {@link ApiKeySource}
 * bean (see {@link FolixScannerConfig#folixPlantNetApiKeySource}) so it can
 * never end up in a settings object that might get logged or serialized.
 *
 * This is the ONLINE fallback used only when Gemini itself fails (see
 * {@link FolixScanService}); it has nothing to do with the frontend's
 * on-device/offline path.
 *
 * @param baseUrl        e.g. https://my-api.plantnet.org (overridable for tests)
 * @param requestTimeout total time allowed for the single Pl@ntNet request
 * @param connectTimeout time allowed to establish the connection
 * @param organs         optional Pl@ntNet {@code organs} hint (leaf/flower/fruit/bark/auto); blank means omit
 * @param lang           optional Pl@ntNet {@code lang} param; blank means omit
 */
public record PlantNetSettings(
        String baseUrl,
        Duration requestTimeout,
        Duration connectTimeout,
        String organs,
        String lang
) {
}
