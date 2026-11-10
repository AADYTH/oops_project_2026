package com.cropscanner.folix;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/** Mirrors GeminiRestVisionClientTest's structure: a local stand-in server, no real network access. */
class PlantNetSpeciesClientTest {

    private static final String KEY = "TEST-PLANTNET-KEY-do-not-leak-0123456789";
    private static final byte[] IMAGE = {1, 2, 3, 4, 5, 6, 7, 8};

    private PlantNetSpeciesClient client(MockPlantNet server) {
        PlantNetSettings settings = new PlantNetSettings(server.baseUrl(), Duration.ofSeconds(5),
                Duration.ofSeconds(2), "auto", "en");
        return new PlantNetSpeciesClient(settings, () -> Optional.of(KEY));
    }

    private static void assertNoSecrets(FolixAnalysisException e) {
        assertFalse(String.valueOf(e.getMessage()).contains(KEY));
        assertFalse(e.clientMessage().contains(KEY));
    }

    @Test
    void sendsExactlyOneWellFormedMultipartRequestAndReturnsTheRawBody() {
        try (MockPlantNet g = new MockPlantNet()) {
            String responseBody = TestSupport.plantNetResponse(0.8, "Solanum lycopersicum", "Tomato");
            g.respond(200, responseBody);

            String out = client(g).identify(IMAGE, "image/jpeg");

            assertEquals(responseBody, out);
            assertEquals(1, g.requests.size(), "a fallback call must make exactly ONE Pl@ntNet request");
            RecordedRequest req = g.requests.get(0);
            assertEquals("POST", req.method());
            assertEquals("/v2/identify/all", req.path());
            assertTrue(req.rawQuery().contains("api-key=" + KEY));
            assertTrue(req.rawQuery().contains("organs=auto"));
            assertTrue(req.rawQuery().contains("lang=en"));
            assertTrue(req.headers().get("content-type").startsWith("multipart/form-data; boundary="));
            assertTrue(req.body().contains("name=\"images\""));
        }
    }

    @Test
    void theKeyIsNeverLoggedOrPutInAnExceptionMessage() {
        try (MockPlantNet g = new MockPlantNet()) {
            g.respond(401, "{\"message\":\"Invalid API key\"}");
            FolixAnalysisException e = assertThrows(FolixAnalysisException.class, () -> client(g).identify(IMAGE, "image/jpeg"));
            assertEquals(FolixAnalysisException.Code.ANALYSIS_UNAVAILABLE, e.code());
            assertNoSecrets(e);
        }
    }

    @Test
    void rateLimitMapsToRetryableRateLimited() {
        try (MockPlantNet g = new MockPlantNet()) {
            g.respond(429, "{\"message\":\"quota exceeded\"}");
            FolixAnalysisException e = assertThrows(FolixAnalysisException.class, () -> client(g).identify(IMAGE, "image/jpeg"));
            assertEquals(FolixAnalysisException.Code.RATE_LIMITED, e.code());
            assertTrue(e.retryable());
        }
    }

    @Test
    void serverErrorMapsToRetryableFailure() {
        try (MockPlantNet g = new MockPlantNet()) {
            g.respond(503, "{\"message\":\"down\"}");
            FolixAnalysisException e = assertThrows(FolixAnalysisException.class, () -> client(g).identify(IMAGE, "image/jpeg"));
            assertEquals(FolixAnalysisException.Code.ANALYSIS_FAILED, e.code());
            assertTrue(e.retryable());
        }
    }

    @Test
    void notConfiguredWithoutAKeyFailsBeforeAnyRequest() {
        try (MockPlantNet g = new MockPlantNet()) {
            PlantNetSettings settings = new PlantNetSettings(g.baseUrl(), Duration.ofSeconds(5),
                    Duration.ofSeconds(2), "auto", "en");
            PlantNetSpeciesClient client = new PlantNetSpeciesClient(settings, Optional::empty);
            assertFalse(client.isConfigured());
            FolixAnalysisException e = assertThrows(FolixAnalysisException.class, () -> client.identify(IMAGE, "image/jpeg"));
            assertEquals(FolixAnalysisException.Code.ANALYSIS_UNAVAILABLE, e.code());
            assertEquals(0, g.requests.size());
        }
    }

    // ------------------------------------------------------------------
    // Local HTTP server standing in for my-api.plantnet.org
    // ------------------------------------------------------------------

    record RecordedRequest(String method, String path, String rawQuery, Map<String, String> headers, String body) { }

    static final class MockPlantNet implements AutoCloseable {
        final HttpServer server;
        final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
        volatile int status = 200;
        volatile String body = "{}";

        MockPlantNet() {
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
                byte[] out = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                try {
                    exchange.sendResponseHeaders(status, out.length);
                    try (OutputStream os = exchange.getResponseBody()) { os.write(out); }
                } finally {
                    exchange.close();
                }
            });
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
        }

        String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        MockPlantNet respond(int status, String body) { this.status = status; this.body = body; return this; }

        @Override public void close() { server.stop(0); }
    }
}
