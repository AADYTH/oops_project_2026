package com.cropscanner.folix;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** Shared builders and a local stand-in for the Gemini endpoint. No network access is ever used. */
final class TestSupport {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private TestSupport() { }

    /** A well-formed, confident diagnosis exactly as the model schema describes it. */
    static Map<String, Object> validDiagnosis() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("plant_detected", true);
        m.put("plant_part", "leaf");
        m.put("crop", "tomato");
        m.put("symptoms", List.of("Brown concentric-ring spots on lower leaves", "Yellow halo around lesions"));
        m.put("reasoning", "Concentric target-like rings with a yellow halo on older leaves are typical of early blight.");
        m.put("disease", "Early blight");
        m.put("severity", "moderate");
        m.put("confidence", "high");
        m.put("needs_human_review", false);
        m.put("recommended_actions", List.of(
                "Remove and dispose of the most affected leaves.",
                "Inspect neighbouring plants for similar spots.",
                "Ask a local agricultural officer to confirm the diagnosis."));
        return m;
    }

    static Map<String, Object> nonPlant() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("plant_detected", false);
        m.put("plant_part", "unknown");
        m.put("crop", "unknown");
        m.put("symptoms", List.of());
        m.put("reasoning", "The image shows a coffee mug on a desk; no plant is visible.");
        m.put("disease", "unknown");
        m.put("severity", "unknown");
        m.put("confidence", "low");
        m.put("needs_human_review", true);
        m.put("recommended_actions", List.of());
        return m;
    }

    static String json(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Wraps model text the way generateContent returns it. */
    static String geminiEnvelope(String modelText, String finishReason) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("text", modelText);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("role", "model");
        content.put("parts", List.of(part));
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("content", content);
        if (finishReason != null) candidate.put("finishReason", finishReason);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("candidates", List.of(candidate));
        return json(root);
    }

    static String geminiEnvelope(Map<String, Object> diagnosis) {
        return geminiEnvelope(json(diagnosis), "STOP");
    }

    // ------------------------------------------------------------------
    // Fake VisionClient for service/controller tests
    // ------------------------------------------------------------------

    static final class FakeVision implements VisionClient {
        boolean configured = true;
        String responseText;
        RuntimeException failure;
        int calls = 0;
        byte[] lastBytes;
        String lastMime;

        FakeVision returning(String text) { this.responseText = text; return this; }
        FakeVision failing(RuntimeException e) { this.failure = e; return this; }

        @Override public boolean isConfigured() { return configured; }

        @Override public String analyze(byte[] imageBytes, String mimeType) {
            calls++;
            lastBytes = imageBytes;
            lastMime = mimeType;
            if (failure != null) throw failure;
            return responseText;
        }
    }

    static FolixScanService service(FakeVision vision) {
        return new FolixScanService(new ImageSanitizer(10 * 1024 * 1024), vision, new AssessmentParser(new ActionSafetyPolicy()));
    }

    /** Wires the Gemini->Pl@ntNet fallback chain with a fake of each so the branching can be tested without HTTP. */
    static FolixScanService serviceWithPlantNet(FakeVision vision, FakePlantNet plantNet) {
        return new FolixScanService(new ImageSanitizer(10 * 1024 * 1024), vision,
                new AssessmentParser(new ActionSafetyPolicy()),
                plantNet == null ? Optional.empty() : Optional.of(plantNet),
                new PlantNetDiagnosisMapper());
    }

    // ------------------------------------------------------------------
    // Fake PlantNetSpeciesClient for FolixScanService fallback tests
    // ------------------------------------------------------------------

    /**
     * A test double for the concrete {@link PlantNetSpeciesClient}: it subclasses the real client
     * (constructed with harmless dummy settings that are never actually used over the network) and
     * overrides the two methods {@link FolixScanService} calls, so the fallback branching in
     * {@code FolixScanService.analyze} can be tested without a real HTTP server.
     */
    static final class FakePlantNet extends PlantNetSpeciesClient {
        boolean configured = true;
        String responseText;
        RuntimeException failure;
        int calls = 0;

        FakePlantNet() {
            super(new PlantNetSettings("http://127.0.0.1:1", java.time.Duration.ofSeconds(5),
                    java.time.Duration.ofSeconds(2), "auto", "en"), () -> Optional.of("fake-plantnet-key"));
        }

        FakePlantNet returning(String text) { this.responseText = text; return this; }
        FakePlantNet failing(RuntimeException e) { this.failure = e; return this; }

        @Override public boolean isConfigured() { return configured; }

        @Override public String identify(byte[] imageBytes, String mimeType) {
            calls++;
            if (failure != null) throw failure;
            return responseText;
        }
    }

    /** A well-formed Pl@ntNet species-identification response (the real /v2/identify shape) with one
     *  confident-looking top result. */
    static String plantNetResponse(double score, String scientificName, String commonName) {
        Map<String, Object> species = new LinkedHashMap<>();
        species.put("scientificNameWithoutAuthor", scientificName);
        species.put("scientificName", scientificName + " L.");
        species.put("commonNames", commonName == null ? List.of() : List.of(commonName));
        species.put("genus", Map.of("scientificName", scientificName.split(" ")[0]));
        species.put("family", Map.of("scientificName", "Solanaceae"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("score", score);
        result.put("species", species);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("query", Map.of());
        root.put("language", "en");
        root.put("results", List.of(result));
        root.put("version", "2026-03-20 (7.5)");
        root.put("remainingIdentificationRequests", 495);
        return json(root);
    }

    static String plantNetEmptyResponse() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("query", Map.of());
        root.put("language", "en");
        root.put("results", List.of());
        root.put("version", "2026-03-20 (7.5)");
        root.put("remainingIdentificationRequests", 495);
        return json(root);
    }

    // ------------------------------------------------------------------
    // Local HTTP server standing in for generativelanguage.googleapis.com
    // ------------------------------------------------------------------

    record RecordedRequest(String method, String path, String rawQuery, Map<String, String> headers, String body) { }

    static final class MockGemini implements AutoCloseable {
        final HttpServer server;
        final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
        volatile int status = 200;
        volatile String body = "{}";
        volatile Map<String, String> responseHeaders = Map.of();
        volatile long delayMillis = 0;

        MockGemini() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            server.createContext("/", exchange -> {
                byte[] in = exchange.getRequestBody().readAllBytes();
                Map<String, String> headers = new LinkedHashMap<>();
                exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), String.join(",", v)));
                requests.add(new RecordedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        exchange.getRequestURI().getRawQuery(), headers, new String(in, StandardCharsets.UTF_8)));
                if (delayMillis > 0) {
                    try { Thread.sleep(delayMillis); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                }
                byte[] out = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                responseHeaders.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
                try {
                    exchange.sendResponseHeaders(status, out.length);
                    try (OutputStream os = exchange.getResponseBody()) { os.write(out); }
                } catch (IOException ignored) {
                    // client gave up (timeout test)
                } finally {
                    exchange.close();
                }
            });
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            server.start();
        }

        String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta"; }

        MockGemini respond(int status, String body) { this.status = status; this.body = body; return this; }

        @Override public void close() { server.stop(0); }
    }

    static GeminiSettings settings(String baseUrl, long timeoutSeconds, String thinkingLevel) {
        return new GeminiSettings(baseUrl, "test-model", java.time.Duration.ofSeconds(timeoutSeconds),
                java.time.Duration.ofSeconds(2), 4096, thinkingLevel);
    }

    static List<String> list(String... s) { return new ArrayList<>(List.of(s)); }
}
