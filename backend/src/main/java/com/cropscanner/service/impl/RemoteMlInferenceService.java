package com.cropscanner.service.impl;

import com.cropscanner.model.DiseaseInfo;
import com.cropscanner.service.InferenceService;
import com.cropscanner.service.RemedyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Talks to the companion Python `inference-service` (FastAPI + a real
 * Hugging Face image-classification model + OpenCV lesion segmentation) over
 * plain HTTP. This is intentionally a thin, real network client rather than
 * an in-JVM "simulation": disease classification is a machine-learning
 * problem, and faking it in Java would defeat the accuracy requirement this
 * system is built for.
 *
 * The request body is a JSON envelope with base64 image bytes to keep the
 * contract simple and framework-agnostic; for large images or high
 * throughput, switch this to multipart/form-data or gRPC.
 */
@Service
public class RemoteMlInferenceService implements InferenceService {

    private static final Logger log = LoggerFactory.getLogger(RemoteMlInferenceService.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RemedyService remedyService;
    private final String inferenceServiceBaseUrl;

    public RemoteMlInferenceService(
            RemedyService remedyService,
            @Value("${cropscanner.inference-service.base-url}") String inferenceServiceBaseUrl,
            @Value("${cropscanner.inference-service.timeout-seconds:20}") long timeoutSeconds) {
        this.remedyService = remedyService;
        this.inferenceServiceBaseUrl = inferenceServiceBaseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                // Force plain HTTP/1.1. The Python inference service (uvicorn) only
                // speaks HTTP/1.1; java.net.http's default is to try an HTTP/2
                // cleartext ("h2c") upgrade first on plaintext http:// URIs. Some
                // servers/network stacks (uvicorn included, on some setups) don't
                // handle that upgrade attempt cleanly, which can corrupt the
                // connection — seen as uvicorn logging "Unsupported upgrade
                // request." / "Invalid HTTP request received.", and on the Java
                // side either a flat-out connection reset or a garbled request
                // that Python's own validation then rejects with a generic 422
                // that has none of our fields (hence blurScore/brightness coming
                // back as -1 — that's Java's own fallback default, not a real
                // reading). Pinning HTTP/1.1 here removes the upgrade attempt
                // entirely, since we know in advance the peer doesn't support h2c.
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public DiseaseInfo classifyAndScore(byte[] imageBytes, String plantPartHint) {
        return classifyAndScore(imageBytes, plantPartHint, false);
    }

    @Override
    public DiseaseInfo classifyAndScore(byte[] imageBytes, String plantPartHint, boolean skipQualityCheck) {
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "image_base64", Base64.getEncoder().encodeToString(imageBytes),
                    "plant_part_hint", plantPartHint == null ? "UNKNOWN" : plantPartHint,
                    "skip_quality_check", skipQualityCheck
            ));

            HttpResponse<String> response = sendWithRetry(payload);

            if (response.statusCode() == 422) {
                JsonNode detail = objectMapper.readTree(response.body()).path("detail");

                // A real image-quality rejection from OUR code is always a JSON
                // OBJECT with an "errorCode" field (see check_image_quality in
                // main.py). If "detail" is anything else — e.g. an ARRAY, which
                // is FastAPI's own built-in shape for a request-validation
                // failure — this 422 has nothing to do with photo quality at
                // all, and mislabeling it that way would send the user chasing
                // the wrong fix. Log the raw body so the real cause is visible.
                if (!detail.isObject() || detail.path("errorCode").isMissingNode()) {
                    log.error("Inference service returned an unexpected 422 (not an image-quality rejection). " +
                            "Raw response body: {}", response.body());
                    throw new InferenceUnavailableException(
                            "The inference service rejected the request in an unexpected way (not a photo-quality " +
                                    "issue). This usually means a request/response mismatch between the backend and " +
                                    "the inference service, or a network layer corrupting the request in transit — " +
                                    "check the backend log for the raw response body.", null);
                }

                JsonNode errorBody = detail;
                String errorCode = errorBody.path("errorCode").asText("IMAGE_QUALITY_REJECTED");

                if ("NO_LEAF_DETECTED".equals(errorCode)) {
                    // Content rejection ("no leaf here"), not a technical-quality rejection ("leaf's here but
                    // unreadable") — kept as a distinct exception type so callers/UI never conflate the two.
                    // See leaf_validator.py and NoLeafDetectedException for why this needs its own path.
                    throw new NoLeafDetectedException(
                            errorBody.path("message").asText("NO LEAF DETECTED — this photo doesn't appear to contain a usable leaf."),
                            errorBody.path("plantMaterialRatio").asDouble(-1),
                            errorBody.path("largestRegionRatio").asDouble(-1));
                }

                throw new ImageQualityException(
                        errorCode,
                        errorBody.path("message").asText("Photo quality is too low to diagnose reliably."),
                        errorBody.path("blurScore").asDouble(-1),
                        errorBody.path("brightness").asDouble(-1));
            }

            if (response.statusCode() != 200) {
                throw new InferenceUnavailableException(
                        "Inference service returned HTTP " + response.statusCode() + ": " + response.body(), null);
            }

            return parseResponse(response.body());

        } catch (InferenceUnavailableException | ImageQualityException | NoLeafDetectedException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to reach inference service at {}", inferenceServiceBaseUrl, e);
            throw new InferenceUnavailableException(
                    "Could not reach ML inference service. The image was NOT diagnosed.", e);
        }
    }

    /**
     * Sends the classify request, retrying once on a transport-level
     * failure (connection reset, "no bytes received", etc.) before giving
     * up. These are usually transient — a brief hiccup in the local
     * connection between this backend and the Python service, sometimes
     * seen right after the very first request while the model is still
     * warming up — not a reason to fail the whole scan outright. Does NOT
     * retry on a clean HTTP error response (4xx/5xx), only on the request
     * never completing at the transport layer.
     */
    private HttpResponse<String> sendWithRetry(String payload) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(inferenceServiceBaseUrl + "/v1/classify"))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        log.info("Calling inference service: {} | payload bytes: {}",
                request.uri(), payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);

        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            log.warn("Transport-level failure calling inference service ({}); retrying once after a short pause.",
                    e.getMessage());
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private DiseaseInfo parseResponse(String body) throws Exception {
        JsonNode json = objectMapper.readTree(body);

        String diseaseName = json.get("disease_name").asText();
        String cropName = json.get("crop_name").asText();
        double confidence = json.get("confidence_percent").asDouble();
        double severity = json.get("severity_percent").asDouble();
        boolean epidemicRisk = json.get("is_epidemic_risk").asBoolean();
        DiseaseInfo.PlantPart plantPart = parsePlantPart(json.get("plant_part").asText());

        DiseaseInfo.ConfidenceLevel confidenceLevel = json.has("confidence_level")
                ? parseConfidenceLevel(json.get("confidence_level").asText())
                : DiseaseInfo.ConfidenceLevel.fromPercent(confidence);

        List<DiseaseInfo.AlternativePrediction> alternatives = new ArrayList<>();
        if (json.has("alternatives") && json.get("alternatives").isArray()) {
            for (JsonNode alt : json.get("alternatives")) {
                alternatives.add(new DiseaseInfo.AlternativePrediction(
                        alt.get("crop_name").asText(),
                        alt.get("disease_name").asText(),
                        alt.get("confidence_percent").asDouble()));
            }
        }

        DiseaseInfo.RemedyPlan remedyPlan = remedyService.findRemedyPlan(diseaseName)
                .orElseGet(() -> remedyService.findRemedyPlan("UNKNOWN")
                        .orElseThrow(() -> new IllegalStateException(
                                "No remedy plan configured even for the UNKNOWN fallback disease entry")));

        // Defaults (true/null) keep this backward-compatible if an older
        // inference-service build (pre part-gating) is ever pointed at by
        // mistake — it just behaves as "part supported" as it always did.
        boolean partSupported = !json.has("part_supported") || json.get("part_supported").asBoolean(true);
        String partWarning = json.has("part_warning") && !json.get("part_warning").isNull()
                ? json.get("part_warning").asText()
                : null;

        List<DiseaseInfo.ModelVote> modelPanel = new ArrayList<>();
        if (json.has("model_panel") && json.get("model_panel").isArray()) {
            for (JsonNode m : json.get("model_panel")) {
                modelPanel.add(new DiseaseInfo.ModelVote(
                        m.get("disease_name").asText(),
                        m.get("crop_name").asText(),
                        m.get("display_name").asText(),
                        m.get("architecture").asText(),
                        m.get("confidence_percent").asDouble(),
                        m.get("is_epidemic_risk").asBoolean()));
            }
        }
        // Backward compatibility: a pre-ensemble inference-service build
        // won't send model_panel at all. Rather than show an empty panel
        // (which would look like a UI bug of its own), synthesize a
        // single-member panel from the top-line result so the frontend's
        // panel display always has something consistent to render.
        if (modelPanel.isEmpty()) {
            modelPanel.add(new DiseaseInfo.ModelVote(diseaseName, cropName, "single-model", "unknown",
                    confidence, epidemicRisk));
        }

        DiseaseInfo.Consensus consensus;
        if (json.has("consensus") && !json.get("consensus").isNull()) {
            JsonNode c = json.get("consensus");
            List<String> agreeingNames = new ArrayList<>();
            if (c.has("agreeing_model_names") && c.get("agreeing_model_names").isArray()) {
                c.get("agreeing_model_names").forEach(n -> agreeingNames.add(n.asText()));
            }
            consensus = new DiseaseInfo.Consensus(
                    c.path("level").asText("SINGLE_MODEL"),
                    c.path("agreeing_models").asInt(1),
                    c.path("total_models").asInt(1),
                    agreeingNames);
        } else {
            consensus = new DiseaseInfo.Consensus("SINGLE_MODEL", 1, 1, List.of("single-model"));
        }

        return new DiseaseInfo(diseaseName, cropName, plantPart, confidence, severity, epidemicRisk,
                remedyPlan, confidenceLevel, alternatives, partSupported, partWarning, modelPanel, consensus);
    }

    private DiseaseInfo.PlantPart parsePlantPart(String raw) {
        try {
            return DiseaseInfo.PlantPart.valueOf(raw.toUpperCase());
        } catch (Exception e) {
            return DiseaseInfo.PlantPart.UNKNOWN;
        }
    }

    private DiseaseInfo.ConfidenceLevel parseConfidenceLevel(String raw) {
        try {
            return DiseaseInfo.ConfidenceLevel.valueOf(raw.toUpperCase());
        } catch (Exception e) {
            return DiseaseInfo.ConfidenceLevel.MEDIUM;
        }
    }

    /** Thrown when the inference service rejects the photo itself as too low-quality to classify. */
    public static class ImageQualityException extends RuntimeException {
        private final String errorCode;
        private final double blurScore;
        private final double brightness;

        public ImageQualityException(String errorCode, String message, double blurScore, double brightness) {
            super(message);
            this.errorCode = errorCode;
            this.blurScore = blurScore;
            this.brightness = brightness;
        }

        public String errorCode() {
            return errorCode;
        }

        public double blurScore() {
            return blurScore;
        }

        public double brightness() {
            return brightness;
        }
    }
}
