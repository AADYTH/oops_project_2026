package com.cropscanner.folix;

import com.cropscanner.service.impl.GeminiKeyPool;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Guards the "no quota circumvention" rule: the Scanner uses one key and never rotates. */
class FolixKeySelectionTest {

    private final FolixScannerConfig config = new FolixScannerConfig();

    @Test
    void usesTheFirstConfiguredKeyFromEitherVariableStyle() {
        GeminiKeyPool pool = new GeminiKeyPool("first-key,second-key", 30, 900,
                new MockEnvironment().withProperty("GEMINI_KEY_1", "third-key"));
        assertEquals(Optional.of("first-key"), config.folixGeminiApiKeySource(pool).get());

        GeminiKeyPool indexedOnly = new GeminiKeyPool("", 30, 900,
                new MockEnvironment().withProperty("GEMINI_KEY_1", "indexed-key").withProperty("GEMINI_KEY_2", "other"));
        assertEquals(Optional.of("indexed-key"), config.folixGeminiApiKeySource(indexedOnly).get());
    }

    @Test
    void neverRotatesEvenAcrossManyRequests() {
        GeminiKeyPool pool = new GeminiKeyPool("k1,k2,k3", 30, 900, new MockEnvironment());
        ApiKeySource source = config.folixGeminiApiKeySource(pool);
        for (int i = 0; i < 25; i++) {
            assertEquals(Optional.of("k1"), source.get());
        }
    }

    @Test
    void aRateLimitedKeyIsNotSwappedForAnother() {
        GeminiKeyPool pool = new GeminiKeyPool("k1,k2", 30, 900, new MockEnvironment());
        ApiKeySource source = config.folixGeminiApiKeySource(pool);
        pool.markUnavailable(1, "rate limited (HTTP 429)");   // legacy cooldown state for key 1
        assertEquals(Optional.of("k1"), source.get(), "the Scanner must not fail over to k2");
    }

    @Test
    void noKeysMeansNoKey() {
        GeminiKeyPool pool = new GeminiKeyPool("", 30, 900, new MockEnvironment());
        assertEquals(Optional.empty(), config.folixGeminiApiKeySource(pool).get());
        GeminiRestVisionClient client = new GeminiRestVisionClient(
                TestSupport.settings("http://127.0.0.1:1/v1beta", 1, ""), config.folixGeminiApiKeySource(pool));
        assertFalse(client.isConfigured());
    }
}
