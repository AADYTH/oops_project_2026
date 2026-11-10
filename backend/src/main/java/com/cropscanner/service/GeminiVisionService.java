package com.cropscanner.service;

import com.cropscanner.model.GeminiOpinion;

/**
 * Boundary to Gemini as an optional, online-only second visual opinion — see
 * README sections "Gemini API key pool" through "Gemini failover logic".
 * This is deliberately a NARROW interface: get one opinion for one image.
 * All failover/key-pool/backoff complexity lives behind the implementation
 * (see {@code impl.RestGeminiVisionService} / {@code impl.GeminiKeyPool}),
 * and all "how does this change the final confidence" logic lives in
 * {@code EvidenceConfidenceService} — this interface itself makes neither
 * decision, so it can never be a place where either concern quietly leaks
 * into the other.
 *
 * Implementations must NEVER throw for an ordinary "Gemini isn't available
 * right now" outcome (disabled, offline, every key rate-limited, request
 * timeout, malformed response) — those are all just
 * {@link GeminiOpinion#unavailable(String)}. An exception here should mean
 * something actually went wrong in a way the caller needs to know about,
 * not "the free tier is temporarily out of quota", which is an expected,
 * routine, non-exceptional outcome for this system.
 */
public interface GeminiVisionService {

    /**
     * @param imageBytes         raw JPEG/PNG bytes of the same photo the local model scored
     * @param localCropGuess     the local model's own crop guess, given to Gemini as context in the
     *                           prompt ONLY to help it describe relevant symptoms — never as an answer
     *                           to confirm; Gemini is asked to identify the disease independently
     * @param localDiseaseGuess  the local model's own disease guess, same caveat as above
     */
    GeminiOpinion getSecondOpinion(byte[] imageBytes, String localCropGuess, String localDiseaseGuess);

    /** Whether this deployment has at least one Gemini key configured at all. */
    boolean isConfigured();
}
