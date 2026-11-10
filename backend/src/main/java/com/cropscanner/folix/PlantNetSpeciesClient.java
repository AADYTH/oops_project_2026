package com.cropscanner.folix;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The ONLINE fallback used by {@link FolixScanService} only when the backend
 * is reachable but {@link GeminiRestVisionClient} itself fails (rate limit,
 * invalid key, timeout, 5xx). This has nothing to do with the frontend's
 * offline/on-device path ({@code scanner-experience.js} /
 * {@code onnx-classifier.js} / {@code offline-classifier.js}), which is
 * untouched by this class.
 *
 * BUGFIX (Issue #5): this used to call a {@code POST /v2/diseases/identify} path that Pl@ntNet's
 * own official API documentation (my.plantnet.org/doc) does not define — only an unofficial
 * third-party wrapper listed such a route, unverified against Pl@ntNet itself. Pl@ntNet's real,
 * documented product is SPECIES identification, not disease diagnosis (my.plantnet.org/doc/api/identify).
 * This class now calls that real, documented endpoint:
 *  - POST {base}/v2/identify/{project} (project defaults to "all", Pl@ntNet's built-in "search every
 *    flora" option — see my.plantnet.org/doc/api/taxonomy)
 *  - auth via the {@code api-key} query parameter, per Pl@ntNet's documented convention (unlike
 *    Gemini's header-based auth)
 *  - request: multipart/form-data with one {@code images} part (JPEG/PNG), optional
 *    {@code organs} / {@code lang} query params
 *  - response: a ranked list of candidate SPECIES with confidence scores — see
 *    {@link PlantNetDiagnosisMapper}, which is responsible for never presenting that species match
 *    as if it were a disease diagnosis.
 *
 * Behavioural guarantees, matching {@link GeminiRestVisionClient}:
 *  - ONE upstream request per {@link #identify} call. No retry loop.
 *  - The API key is never logged and never appears in an exception message;
 *    log lines use only the request path, never the full URI (which carries
 *    the key as a query parameter).
 *  - Redirects are never followed.
 *  - Every failure surfaces as a {@link FolixAnalysisException}.
 */
public class PlantNetSpeciesClient {

    private static final Logger log = LoggerFactory.getLogger(PlantNetSpeciesClient.class);

    private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Za-z0-9_ .-]{1,64}");
    /** "all" is Pl@ntNet's built-in flora covering every species it knows, not scoped to one region. */
    private static final String PATH = "/v2/identify/all";

    private final PlantNetSettings settings;
    private final ApiKeySource keys;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;
    private final String misconfiguration;

    public PlantNetSpeciesClient(PlantNetSettings settings, ApiKeySource keys) {
        this.settings = settings;
        this.keys = keys;
        this.http = HttpClient.newBuilder()
                .connectTimeout(settings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        String problem = null;
        try {
            if (URI.create(settings.baseUrl()).getHost() == null) {
                problem = "folix.plantnet.base-url is not a valid absolute URL";
            }
        } catch (RuntimeException e) {
            problem = "folix.plantnet.base-url is not a valid URL";
        }
        this.misconfiguration = problem;
        if (problem != null) {
            log.error("FOLIX Scanner Pl@ntNet fallback client is misconfigured: {}. The fallback will be skipped.", problem);
        }
    }

    public boolean isConfigured() {
        return misconfiguration == null && keys.get().isPresent();
    }

    /**
     * Sends ONE image to Pl@ntNet's disease-identification endpoint and returns the raw JSON response
     * body for {@link PlantNetDiagnosisMapper} to interpret.
     *
     * @throws FolixAnalysisException for every failure; never returns a partial or fabricated answer
     */
    public String identify(byte[] imageBytes, String mimeType) {
        if (misconfiguration != null) {
            throw FolixAnalysisException.unavailable(misconfiguration);
        }
        Optional<String> key = keys.get();
        if (key.isEmpty()) {
            throw FolixAnalysisException.unavailable("no Pl@ntNet API key configured");
        }

        URI endpoint = buildEndpoint(key.get());
        String boundary = "FolixPlantNet" + UUID.randomUUID().toString().replace("-", "");
        byte[] body;
        try {
            body = buildMultipartBody(boundary, imageBytes, mimeType);
        } catch (IOException e) {
            throw FolixAnalysisException.failed("could not build the Pl@ntNet request body", false);
        }

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(settings.requestTimeout())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        long started = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            log.warn("Pl@ntNet request timed out after {} ms", elapsedMillis(started));
            throw FolixAnalysisException.timeout("Pl@ntNet request timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw FolixAnalysisException.failed("Pl@ntNet request interrupted", true);
        } catch (IOException e) {
            // Only the exception CLASS is logged: messages from lower layers are not guaranteed credential-free.
            log.warn("Pl@ntNet request failed with {} after {} ms", e.getClass().getSimpleName(), elapsedMillis(started));
            throw FolixAnalysisException.failed("network error talking to Pl@ntNet (" + e.getClass().getSimpleName() + ")", true);
        }

        int status = response.statusCode();
        long elapsed = elapsedMillis(started);
        if (status == 200) {
            log.info("Pl@ntNet responded HTTP 200 in {} ms (image {} bytes)", elapsed, imageBytes.length);
            return response.body();
        }

        String errorToken = safeErrorToken(response.body());
        log.warn("Pl@ntNet responded HTTP {}{} in {} ms (path {})",
                status, errorToken == null ? "" : " (" + errorToken + ")", elapsed, PATH);
        throw mapErrorStatus(status, response);
    }

    // ------------------------------------------------------------------
    // Request
    // ------------------------------------------------------------------

    private URI buildEndpoint(String key) {
        String b = settings.baseUrl().endsWith("/")
                ? settings.baseUrl().substring(0, settings.baseUrl().length() - 1)
                : settings.baseUrl();
        StringBuilder query = new StringBuilder();
        query.append("api-key=").append(urlEncode(key));
        if (settings.organs() != null && !settings.organs().isBlank()) {
            query.append("&organs=").append(urlEncode(settings.organs().trim()));
        }
        if (settings.lang() != null && !settings.lang().isBlank()) {
            query.append("&lang=").append(urlEncode(settings.lang().trim()));
        }
        return URI.create(b + PATH + "?" + query);
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Single-image multipart/form-data body, field name "images" per Pl@ntNet's documented request shape. */
    private static byte[] buildMultipartBody(String boundary, byte[] imageBytes, String mimeType) throws IOException {
        String fileName = "scan." + (mimeType != null && mimeType.contains("png") ? "png" : "jpg");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAscii(out, "--" + boundary + "\r\n");
        writeAscii(out, "Content-Disposition: form-data; name=\"images\"; filename=\"" + fileName + "\"\r\n");
        writeAscii(out, "Content-Type: " + (mimeType == null ? "application/octet-stream" : mimeType) + "\r\n\r\n");
        out.write(imageBytes);
        writeAscii(out, "\r\n--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // Response
    // ------------------------------------------------------------------

    private FolixAnalysisException mapErrorStatus(int status, HttpResponse<String> response) {
        switch (status) {
            case 429:
                return FolixAnalysisException.rateLimited(null, "Pl@ntNet rate limit (HTTP 429)");
            case 401:
            case 403:
            case 404:
                return FolixAnalysisException.unavailable("Pl@ntNet rejected the configuration (HTTP " + status + ")");
            case 400:
                return FolixAnalysisException.failed("Pl@ntNet rejected the request (HTTP 400)", false);
            case 504:
                return FolixAnalysisException.timeout("Pl@ntNet deadline exceeded (HTTP 504)");
            default:
                if (status >= 500) {
                    return FolixAnalysisException.failed("Pl@ntNet server error (HTTP " + status + ")", true);
                }
                return FolixAnalysisException.failed("unexpected Pl@ntNet response (HTTP " + status + ")", false);
        }
    }

    /** Extracts a short, safe token from an error body's {@code message} field for logs — bounded length,
     *  restricted character set, never the raw free-text body (which could in principle echo request content). */
    private String safeErrorToken(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode root = mapper.readTree(body);
            if (root == null) return null;
            JsonNode node = root.path("message");
            if (!node.isTextual()) return null;
            String v = node.textValue();
            return SAFE_TOKEN.matcher(v).matches() ? v : null;
        } catch (JsonProcessingException | RuntimeException e) {
            return null;
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }
}
