package com.cropscanner.folix;

/**
 * Boundary to the primary vision model. The FOLIX Scanner has exactly one
 * implementation ({@link GeminiRestVisionClient}): if this cannot produce an
 * answer it throws {@link FolixAnalysisException} — it never returns a
 * partial or fabricated result, and never retries or rotates keys internally.
 *
 * {@link FolixScanService} may retry a {@link FolixAnalysisException} from
 * this interface against {@link PlantNetSpeciesClient} as a second-tier
 * ONLINE fallback (see its class docs). That fallback chain lives entirely
 * in {@code FolixScanService} and is orthogonal to this interface: a
 * {@code VisionClient} implementation itself still has no local-model
 * fallback of its own. This is also unrelated to the frontend's separate
 * offline/on-device path, which lives entirely in the browser and never
 * involves this backend interface.
 */
public interface VisionClient {

    /** Whether Gemini credentials are configured. When false, {@link #analyze} must not attempt a request. */
    boolean isConfigured();

    /**
     * Sends ONE image for analysis and returns the model's raw JSON text.
     * Makes at most one upstream request — no internal retries, no key rotation.
     *
     * @throws FolixAnalysisException for every failure; never returns a partial or fabricated answer
     */
    String analyze(byte[] imageBytes, String mimeType);
}
