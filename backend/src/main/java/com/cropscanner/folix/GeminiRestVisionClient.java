package com.cropscanner.folix;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The FOLIX Scanner's only path to a diagnosis: one HTTPS call to Gemini's
 * {@code generateContent} REST endpoint.
 *
 * Request format (verified against Google's current documentation, Sept 2026):
 *  - POST {base}/models/{model}:generateContent
 *  - auth via the {@code x-goog-api-key} HEADER (never the {@code ?key=} query
 *    string, so the credential cannot end up in URLs, proxy logs or exceptions)
 *  - inline image bytes ({@code inlineData}), 20 MB total-request ceiling
 *  - structured output via {@code generationConfig.responseMimeType} +
 *    {@code responseSchema}
 *  - NO {@code temperature}/{@code topP}/{@code topK}: Google deprecated these
 *    for Gemini 3.x models (July 2026) and newer models ignore them
 *
 * Why generateContent rather than the newer Interactions API: Google documents
 * generateContent as "fully supported" and it is stateless. The Interactions API
 * stores interactions server-side by default (store=true), which is the wrong
 * default for farmers' photographs, and its own documentation pages currently
 * disagree about the endpoint version and response_format shape. If Google
 * later retires generateContent, this is the ONE class to change.
 *
 * Hard behavioural guarantees (each covered by GeminiRestVisionClientTest):
 *  - ONE upstream request per {@link #analyze} call. No retry loop, no second
 *    attempt on 429/5xx/timeout, no key rotation, no model fallback.
 *  - No key/request/response content is ever logged or put in an exception message.
 *  - Redirects are never followed (so the key header can't be replayed elsewhere).
 *  - Every failure surfaces as a {@link FolixAnalysisException}.
 */
public class GeminiRestVisionClient implements VisionClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiRestVisionClient.class);

    private static final Pattern SAFE_MODEL = Pattern.compile("[A-Za-z0-9._-]{1,80}");
    private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Z_]{2,48}");
    private static final int MAX_RETRY_AFTER_SECONDS = 300;

    private final GeminiSettings settings;
    private final ApiKeySource keys;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;
    private final URI endpoint;
    private final String misconfiguration;

    public GeminiRestVisionClient(GeminiSettings settings, ApiKeySource keys) {
        this.settings = settings;
        this.keys = keys;
        this.http = HttpClient.newBuilder()
                .connectTimeout(settings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        URI uri = null;
        String problem = null;
        if (settings.model() == null || !SAFE_MODEL.matcher(settings.model()).matches()) {
            problem = "folix.gemini.model is missing or contains unsafe characters";
        } else {
            try {
                String base = settings.baseUrl().endsWith("/")
                        ? settings.baseUrl().substring(0, settings.baseUrl().length() - 1)
                        : settings.baseUrl();
                uri = URI.create(base + "/models/" + settings.model() + ":generateContent");
            } catch (RuntimeException e) {
                problem = "folix.gemini.base-url is not a valid URL";
            }
        }
        this.endpoint = uri;
        this.misconfiguration = problem;
        if (problem != null) {
            log.error("FOLIX Scanner Gemini client is misconfigured: {}. Scans will be refused until fixed.", problem);
        }
    }

    @Override
    public boolean isConfigured() {
        return misconfiguration == null && keys.get().isPresent();
    }

    @Override
    public String analyze(byte[] imageBytes, String mimeType) {
        if (misconfiguration != null) {
            throw FolixAnalysisException.unavailable(misconfiguration);
        }
        Optional<String> key = keys.get();
        if (key.isEmpty()) {
            throw FolixAnalysisException.unavailable("no Gemini API key configured");
        }

        String payload;
        try {
            payload = mapper.writeValueAsString(buildRequestBody(imageBytes, mimeType));
        } catch (JsonProcessingException e) {
            throw FolixAnalysisException.failed("could not build the request body", false);
        }

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(settings.requestTimeout())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("x-goog-api-key", key.get())
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        long started = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            log.warn("Gemini request timed out after {} ms (model {})", elapsedMillis(started), settings.model());
            throw FolixAnalysisException.timeout("Gemini request timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw FolixAnalysisException.failed("Gemini request interrupted", true);
        } catch (IOException e) {
            // Only the exception CLASS is logged: messages from lower layers are not guaranteed credential-free.
            log.warn("Gemini request failed with {} after {} ms (model {})",
                    e.getClass().getSimpleName(), elapsedMillis(started), settings.model());
            throw FolixAnalysisException.failed("network error talking to Gemini (" + e.getClass().getSimpleName() + ")", true);
        }

        int status = response.statusCode();
        long elapsed = elapsedMillis(started);
        if (status == 200) {
            log.info("Gemini responded HTTP 200 in {} ms (model {}, image {} bytes)", elapsed, settings.model(), imageBytes.length);
            return extractText(response.body());
        }

        String googleStatus = googleErrorStatus(response.body());
        log.warn("Gemini responded HTTP {}{} in {} ms (model {})",
                status, googleStatus == null ? "" : " " + googleStatus, elapsed, settings.model());
        throw mapErrorStatus(status, response);
    }

    // ------------------------------------------------------------------
    // Request
    // ------------------------------------------------------------------

    /**
     * Explicit LinkedHashMap/List construction: heterogeneous nested Map.of(...) generics are an easy
     * way to serialize something other than intended. The text part comes BEFORE the image part, as
     * Google's image-understanding guidance recommends for a single image.
     */
    private Map<String, Object> buildRequestBody(byte[] imageBytes, String mimeType) {
        Map<String, Object> systemText = new LinkedHashMap<>();
        systemText.put("text", AssessmentPrompt.SYSTEM_INSTRUCTION);
        Map<String, Object> systemInstruction = new LinkedHashMap<>();
        systemInstruction.put("parts", List.of(systemText));

        Map<String, Object> userText = new LinkedHashMap<>();
        userText.put("text", AssessmentPrompt.USER_TEXT);

        Map<String, Object> inlineData = new LinkedHashMap<>();
        inlineData.put("mimeType", mimeType);
        inlineData.put("data", Base64.getEncoder().encodeToString(imageBytes));
        Map<String, Object> imagePart = new LinkedHashMap<>();
        imagePart.put("inlineData", inlineData);

        List<Object> parts = new ArrayList<>();
        parts.add(userText);
        parts.add(imagePart);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("role", "user");
        content.put("parts", parts);

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("responseMimeType", "application/json");
        generationConfig.put("responseSchema", AssessmentPrompt.responseSchema());
        generationConfig.put("maxOutputTokens", settings.maxOutputTokens());
        String level = settings.thinkingLevel();
        if (level != null && !level.isBlank()) {
            Map<String, Object> thinking = new LinkedHashMap<>();
            thinking.put("thinkingLevel", level.trim().toUpperCase(java.util.Locale.ROOT));
            generationConfig.put("thinkingConfig", thinking);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemInstruction", systemInstruction);
        body.put("contents", List.of(content));
        body.put("generationConfig", generationConfig);
        return body;
    }

    // ------------------------------------------------------------------
    // Response
    // ------------------------------------------------------------------

    private String extractText(String body) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw FolixAnalysisException.failed("Gemini returned a non-JSON body", true);
        }
        if (root == null || !root.isObject()) {
            throw FolixAnalysisException.failed("Gemini returned an unexpected body shape", true);
        }

        JsonNode feedback = root.get("promptFeedback");
        if (feedback != null) {
            String reason = safeToken(feedback.get("blockReason"));
            if (reason != null && !reason.equals("BLOCK_REASON_UNSPECIFIED")) {
                throw FolixAnalysisException.failed("prompt blocked by Gemini (" + reason + ")", false);
            }
        }

        JsonNode candidates = root.get("candidates");
        if (candidates == null || !candidates.isArray() || candidates.size() == 0) {
            throw FolixAnalysisException.failed("Gemini returned no candidates", true);
        }
        JsonNode candidate = candidates.get(0);

        String finish = safeToken(candidate.get("finishReason"));
        if (finish != null && !finish.equals("STOP")) {
            if (finish.equals("MAX_TOKENS")) {
                throw FolixAnalysisException.failed("model output truncated (MAX_TOKENS)", true);
            }
            throw FolixAnalysisException.failed("generation stopped by Gemini (" + finish + ")", false);
        }

        JsonNode parts = candidate.path("content").path("parts");
        StringBuilder text = new StringBuilder();
        if (parts.isArray()) {
            for (JsonNode part : parts) {
                JsonNode thought = part.get("thought");
                boolean isThought = thought != null && thought.isBoolean() && thought.booleanValue();
                JsonNode t = part.get("text");
                if (!isThought && t != null && t.isTextual()) {
                    text.append(t.textValue());
                }
            }
        }
        if (text.toString().isBlank()) {
            throw FolixAnalysisException.failed("Gemini returned an empty answer", true);
        }
        return text.toString();
    }

    private FolixAnalysisException mapErrorStatus(int status, HttpResponse<String> response) {
        switch (status) {
            case 429:
                return FolixAnalysisException.rateLimited(parseRetryAfter(response), "Gemini rate limit (HTTP 429)");
            case 401:
            case 402:
            case 403:
            case 404:
                // Bad/expired/unauthorized key, billing exhausted, model not found: an operator problem.
                return FolixAnalysisException.unavailable("Gemini rejected the configuration (HTTP " + status + ")");
            case 400:
                if (indicatesInvalidKey(response.body())) {
                    return FolixAnalysisException.unavailable("Gemini rejected the API key (HTTP 400)");
                }
                return FolixAnalysisException.failed("Gemini rejected the request (HTTP 400)", false);
            case 504:
                return FolixAnalysisException.timeout("Gemini deadline exceeded (HTTP 504)");
            default:
                if (status >= 500) {
                    return FolixAnalysisException.failed("Gemini server error (HTTP " + status + ")", true);
                }
                return FolixAnalysisException.failed("unexpected Gemini response (HTTP " + status + ")", false);
        }
    }

    private static boolean indicatesInvalidKey(String body) {
        if (body == null) return false;
        return body.contains("API_KEY_INVALID") || body.contains("API key not valid")
                || body.contains("API key expired");
    }

    private static Integer parseRetryAfter(HttpResponse<String> response) {
        Optional<String> header = response.headers().firstValue("Retry-After");
        if (header.isEmpty()) return null;
        try {
            int seconds = Integer.parseInt(header.get().trim());
            if (seconds < 1) return null;
            return Math.min(seconds, MAX_RETRY_AFTER_SECONDS);
        } catch (NumberFormatException e) {
            return null; // HTTP-date form: ignore rather than guess
        }
    }

    /** Extracts Google's {@code error.status} enum (e.g. RESOURCE_EXHAUSTED) for logs — never the free-text message. */
    private String googleErrorStatus(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode root = mapper.readTree(body);
            if (root == null) return null;
            return safeToken(root.path("error").get("status"));
        } catch (JsonProcessingException | RuntimeException e) {
            return null;
        }
    }

    /** Returns the node's text only if it is a short SCREAMING_SNAKE enum token, so nothing free-form is ever logged. */
    private static String safeToken(JsonNode node) {
        if (node == null || !node.isTextual()) return null;
        String v = node.textValue();
        return SAFE_TOKEN.matcher(v).matches() ? v : null;
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }
}
