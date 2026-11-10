package com.cropscanner.folix;

import java.time.Duration;

/**
 * Non-secret Gemini call settings. (The API key is intentionally NOT here; it
 * comes from {@link ApiKeySource} and is never stored in a settings object that
 * might get logged.)
 *
 * @param baseUrl        e.g. https://generativelanguage.googleapis.com/v1beta (overridable for tests)
 * @param model          e.g. gemini-3.5-flash-lite
 * @param requestTimeout total time allowed for the single Gemini request
 * @param connectTimeout time allowed to establish the connection
 * @param maxOutputTokens cap on generated tokens (thinking tokens count toward it)
 * @param thinkingLevel  optional; blank means "use the model's default". Not all models accept all
 *                       levels (e.g. "minimal" is rejected by 3.7/3.8 Flash), so it is opt-in.
 */
public record GeminiSettings(
        String baseUrl,
        String model,
        Duration requestTimeout,
        Duration connectTimeout,
        int maxOutputTokens,
        String thinkingLevel
) {
}
