package com.cropscanner.service.impl;

import com.cropscanner.model.GeminiOpinion;
import com.cropscanner.service.GeminiVisionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Real HTTPS client for Gemini's {@code generateContent} REST endpoint,
 * used strictly as an optional, free-tier-only second visual opinion — see
 * README "Gemini API key pool" / "No paid fallback" / "Gemini's role".
 *
 * What this class deliberately does NOT do:
 *  - Never sets a billing header, never switches MODEL to a paid-tier-only
 *    model, never retries with a "bigger" model on failure. GEMINI_MODEL is
 *    the one model this ever calls, full stop (see {@link #model}).
 *  - Never creates a Gemini/Google Cloud account, API key, or project. It
 *    only USES keys the operator already put in the environment.
 *  - Never lets Gemini's answer overwrite the local model's crop/disease —
 *    it returns Gemini's opinion as data; {@code EvidenceConfidenceService}
 *    is the only place that data is combined with anything, and even there
 *    it only adjusts a confidence tier (see that class).
 *  - Never loops retrying a failed key — one attempt per configured key,
 *    in {@link GeminiKeyPool}'s round-robin order, then gives up for this
 *    scan and reports unavailable. The pool's cooldown (not this class)
 *    is what prevents hammering a struggling/rate-limited key on the NEXT
 *    scan.
 */
@Service
public class RestGeminiVisionService implements GeminiVisionService {

    private static final Logger log = LoggerFactory.getLogger(RestGeminiVisionService.class);

    private static final String PROMPT = """
            You are assisting a plant-disease scanning app. Look at this leaf photo and identify the crop and \
            any visible disease. Respond with ONLY a JSON object, no markdown fences, no extra text, in exactly \
            this shape:
            {"crop": "<crop name or null if unclear>", "disease": "<disease name, or \\"Healthy\\" if no \
            disease is visible, or null if you cannot tell>", "evidence": "<one sentence describing the visible \
            symptoms that led to this answer>"}
            Judge only from what is visible in the image. If the photo does not show a leaf clearly enough to \
            judge, set crop and disease to null and say so in evidence.""";

    private final GeminiKeyPool keyPool;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;
    private final boolean enabled;
    private final String model;
    private final Duration requestTimeout;

    public RestGeminiVisionService(
            GeminiKeyPool keyPool,
            @Value("${cropscanner.gemini.enabled:false}") boolean enabled,
            // Free-tier-eligible model. Verify current free-tier availability at ai.google.dev before
            // relying on this default — Google's lineup and free-tier eligibility change over time (the
            // Gemini 2.5 line, for example, is on a published deprecation schedule). Override via
            // GEMINI_MODEL / cropscanner.gemini.model without a code change if the default goes stale.
            @Value("${cropscanner.gemini.model:gemini-3.1-flash-lite}") String model,
            @Value("${cropscanner.gemini.timeout-seconds:15}") long timeoutSeconds) {
        this.keyPool = keyPool;
        this.enabled = enabled;
        this.model = model;
        this.requestTimeout = Duration.ofSeconds(timeoutSeconds);
        this.httpClient = HttpClient.newBuilder().connectTimeout(requestTimeout).build();

        if (enabled && !keyPool.hasAnyKeyConfigured()) {
            log.warn("cropscanner.gemini.enabled=true but no Gemini keys are configured "
                    + "(GEMINI_API_KEYS / GEMINI_KEY_1..N). Every scan will silently run on the local model "
                    + "only until at least one key is set — this is intentional (no paid fallback, no account "
                    + "creation), just flagging so it's not mistaken for a bug.");
        } else if (enabled) {
            log.info("Gemini second-opinion ENABLED with model '{}' and {} configured key(s).",
                    model, keyPool.configuredKeyCount());
        } else {
            log.info("Gemini second-opinion is disabled (cropscanner.gemini.enabled=false). "
                    + "CropGuard runs on the local model only.");
        }
    }

    @Override
    public boolean isConfigured() {
        return enabled && keyPool.hasAnyKeyConfigured();
    }

    @Override
    public GeminiOpinion getSecondOpinion(byte[] imageBytes, String localCropGuess, String localDiseaseGuess) {
        if (!enabled) {
            return GeminiOpinion.notRequested("Gemini second opinion is disabled (cropscanner.gemini.enabled=false)");
        }
        if (!keyPool.hasAnyKeyConfigured()) {
            return GeminiOpinion.notRequested("No Gemini API keys configured");
        }

        // Try keys until one works or the pool has nothing left to offer. Each key gets exactly ONE
        // attempt per scan — no per-key retry loop (see class docstring).
        int attemptsRemaining = keyPool.configuredKeyCount();
        while (attemptsRemaining-- > 0) {
            Optional<GeminiKeyPool.SelectedKey> selected = keyPool.selectAvailableKey();
            if (selected.isEmpty()) {
                return GeminiOpinion.unavailable("All configured Gemini keys are currently in cooldown "
                        + "(rate-limited/quota-exhausted) or none are configured");
            }
            GeminiKeyPool.SelectedKey key = selected.get();
            try {
                Optional<GeminiOpinion> result = callGemini(key, imageBytes);
                if (result.isPresent()) {
                    keyPool.markSuccess(key.poolIndex());
                    return result.get();
                }
                // callGemini returning empty means "this key failed in a way that should try the next
                // one" — it already called markUnavailable internally with the specific reason.
            } catch (Exception e) {
                keyPool.markUnavailable(key.poolIndex(), "unexpected error: " + e.getClass().getSimpleName());
                log.error("Unexpected error calling Gemini with key {}: {}", key.poolIndex(), e.getMessage());
            }
        }

        return GeminiOpinion.unavailable("Every configured Gemini key failed for this scan");
    }

    /**
     * Built with explicit LinkedHashMap/ArrayList<Object> rather than nested
     * Map.of(...)/List.of(...) — heterogeneous-value nested generic factory
     * calls like that are exactly the kind of thing that either needs
     * explicit type witnesses or silently picks a wildcard-capture type the
     * author didn't intend; explicit Map<String,Object> here removes any
     * ambiguity about what gets serialized.
     */
    private Map<String, Object> buildRequestBody(byte[] imageBytes) {
        Map<String, Object> textPart = new java.util.LinkedHashMap<>();
        textPart.put("text", PROMPT);

        Map<String, Object> inlineData = new java.util.LinkedHashMap<>();
        inlineData.put("mime_type", "image/jpeg");
        inlineData.put("data", Base64.getEncoder().encodeToString(imageBytes));
        Map<String, Object> imagePart = new java.util.LinkedHashMap<>();
        imagePart.put("inline_data", inlineData);

        Map<String, Object> content = new java.util.LinkedHashMap<>();
        content.put("parts", List.of(textPart, imagePart));

        Map<String, Object> generationConfig = new java.util.LinkedHashMap<>();
        generationConfig.put("temperature", 0.1);
        generationConfig.put("maxOutputTokens", 200);

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("contents", List.of(content));
        body.put("generationConfig", generationConfig);
        return body;
    }

    /**
     * One attempt against one key. Returns empty (not throwing) for any
     * failure that should be treated as "try the next key" — a genuine
     * exception is reserved for something the caller couldn't have
     * anticipated from HTTP status codes alone.
     */
    private Optional<GeminiOpinion> callGemini(GeminiKeyPool.SelectedKey key, byte[] imageBytes) throws Exception {
        String payload = objectMapper.writeValueAsString(buildRequestBody(imageBytes));

        // Note: the API key travels as a query parameter, per Gemini's own REST convention — this is
        // Google's documented auth mechanism for this endpoint, not a shortcut we invented. It is never
        // logged (see the URI is only ever referenced by pool index in log lines below).
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/" + model
                        + ":generateContent?key=" + key.key()))
                .header("Content-Type", "application/json")
                .timeout(requestTimeout)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            keyPool.markUnavailable(key.poolIndex(), "network error: " + e.getMessage());
            return Optional.empty();
        }

        if (response.statusCode() == 429) {
            keyPool.markUnavailable(key.poolIndex(), "rate limited (HTTP 429)");
            return Optional.empty();
        }
        if (response.statusCode() == 403 || response.statusCode() == 401) {
            // Invalid/revoked/quota-exhausted-with-billing-required key. Long cooldown — this isn't
            // something that resolves itself in seconds like a 429 does, and we must NEVER respond to
            // this by upgrading to a paid tier or attempting to fix the key ourselves.
            keyPool.markUnavailable(key.poolIndex(), "auth/permission error (HTTP " + response.statusCode() + ")");
            return Optional.empty();
        }
        if (response.statusCode() >= 500) {
            keyPool.markUnavailable(key.poolIndex(), "Gemini server error (HTTP " + response.statusCode() + ")");
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            keyPool.markUnavailable(key.poolIndex(), "unexpected HTTP " + response.statusCode());
            log.warn("Gemini returned unexpected HTTP {} for key {}: {}", response.statusCode(), key.poolIndex(),
                    truncate(response.body()));
            return Optional.empty();
        }

        return parseSuccessResponse(response.body());
    }

    private Optional<GeminiOpinion> parseSuccessResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode candidates = root.path("candidates");
            if (!candidates.isArray() || candidates.isEmpty()) {
                log.warn("Gemini response had no candidates: {}", truncate(body));
                return Optional.empty();
            }
            String text = candidates.get(0).path("content").path("parts").path(0).path("text").asText("");
            String jsonText = stripMarkdownFences(text).trim();
            JsonNode parsed = objectMapper.readTree(jsonText);

            String crop = textOrNull(parsed, "crop");
            String disease = textOrNull(parsed, "disease");
            String evidence = textOrNull(parsed, "evidence");

            return Optional.of(new GeminiOpinion(true, true, crop, disease, evidence, null, null));
        } catch (Exception e) {
            log.warn("Could not parse Gemini response as the expected JSON shape: {} (raw: {})",
                    e.getMessage(), truncate(body));
            return Optional.empty();
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        String text = value.asText();
        return (text == null || text.isBlank() || text.equalsIgnoreCase("null")) ? null : text;
    }

    private static String stripMarkdownFences(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```[a-zA-Z]*\\s*", "");
            int lastFence = trimmed.lastIndexOf("```");
            if (lastFence >= 0) {
                trimmed = trimmed.substring(0, lastFence);
            }
        }
        return trimmed;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
