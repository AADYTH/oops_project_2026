package com.cropscanner.folix;

import java.util.Optional;

/**
 * Supplies the ONE Gemini API key the Scanner uses. Deliberately not a pool:
 * an implementation must return the same key on every call and must not react
 * to rate-limit responses. See {@link FolixScannerConfig#geminiApiKeySource}.
 */
@FunctionalInterface
public interface ApiKeySource {
    Optional<String> get();
}
