package com.cropscanner.folix;

import com.cropscanner.folix.TestSupport.MockGemini;
import com.cropscanner.folix.TestSupport.RecordedRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class GeminiRestVisionClientTest {

    private static final String KEY = "TEST-KEY-do-not-leak-0123456789";
    private static final byte[] IMAGE = {1, 2, 3, 4, 5, 6, 7, 8};

    private GeminiRestVisionClient client(MockGemini gemini) {
        return new GeminiRestVisionClient(TestSupport.settings(gemini.baseUrl(), 5, ""), () -> Optional.of(KEY));
    }

    private FolixAnalysisException failure(MockGemini gemini) {
        return assertThrows(FolixAnalysisException.class, () -> client(gemini).analyze(IMAGE, "image/jpeg"));
    }

    private static void assertNoSecrets(FolixAnalysisException e) {
        assertFalse(String.valueOf(e.getMessage()).contains(KEY));
        assertFalse(e.clientMessage().contains(KEY));
    }

    // ---------------- request shape ----------------

    @Test
    void sendsExactlyOneWellFormedRequestAndReturnsTheModelText() throws JsonProcessingException {
        try (MockGemini g = new MockGemini()) {
            String modelText = TestSupport.json(TestSupport.validDiagnosis());
            g.respond(200, TestSupport.geminiEnvelope(modelText, "STOP"));

            String out = client(g).analyze(IMAGE, "image/jpeg");

            assertEquals(modelText, out);
            assertEquals(1, g.requests.size(), "a scan must make exactly ONE Gemini request");
            RecordedRequest req = g.requests.get(0);
            assertEquals("POST", req.method());
            assertEquals("/v1beta/models/test-model:generateContent", req.path());

            // Credential: header only. Never in the URL, never in the body.
            assertNull(req.rawQuery(), "no query string at all, in particular no ?key=");
            assertEquals(KEY, req.headers().get("x-goog-api-key"));
            assertFalse(req.body().contains(KEY));
            assertFalse(req.path().contains(KEY));

            JsonNode body = TestSupport.MAPPER.readTree(req.body());
            assertTrue(body.get("systemInstruction").get("parts").get(0).get("text").textValue().contains("FOLIX"));

            JsonNode parts = body.get("contents").get(0).get("parts");
            assertEquals(2, parts.size());
            assertTrue(parts.get(0).get("text").isTextual(), "text part comes before the image part");
            JsonNode inline = parts.get(1).get("inlineData");
            assertEquals("image/jpeg", inline.get("mimeType").textValue());
            assertArrayEquals(IMAGE, Base64.getDecoder().decode(inline.get("data").textValue()));

            JsonNode cfg = body.get("generationConfig");
            assertEquals("application/json", cfg.get("responseMimeType").textValue());
            assertEquals("OBJECT", cfg.get("responseSchema").get("type").textValue());
            assertTrue(cfg.get("maxOutputTokens").isNumber());
            assertNull(cfg.get("thinkingConfig"), "nothing thinking-related is sent unless configured");
        }
    }

    @Test
    void neverSendsDeprecatedSamplingParametersOrOtherUnsupportedFields() {
        try (MockGemini g = new MockGemini()) {
            g.respond(200, TestSupport.geminiEnvelope(TestSupport.validDiagnosis()));
            client(g).analyze(IMAGE, "image/png");
            String body = g.requests.get(0).body();
            for (String forbidden : new String[]{"temperature", "topP", "topK", "top_p", "top_k", "candidateCount",
                    "frequencyPenalty", "presencePenalty", "thinkingBudget", "tools", "safetySettings"}) {
                assertFalse(body.contains("\"" + forbidden + "\""), "must not send " + forbidden);
            }
        }
    }

    @Test
    void responseSchemaRequiresEveryContractField() throws JsonProcessingException {
        try (MockGemini g = new MockGemini()) {
            g.respond(200, TestSupport.geminiEnvelope(TestSupport.validDiagnosis()));
            client(g).analyze(IMAGE, "image/jpeg");
            JsonNode schema = TestSupport.MAPPER.readTree(g.requests.get(0).body())
                    .get("generationConfig").get("responseSchema");
            assertEquals(11, schema.get("required").size());
            assertEquals(11, schema.get("properties").size());
            assertEquals(5, schema.get("properties").get("plant_part").get("enum").size());
            assertEquals(4, schema.get("properties").get("severity").get("enum").size());
            assertEquals(3, schema.get("properties").get("confidence").get("enum").size());
        }
    }

    @Test
    void thinkingLevelIsSentOnlyWhenExplicitlyConfigured() throws JsonProcessingException {
        try (MockGemini g = new MockGemini()) {
            g.respond(200, TestSupport.geminiEnvelope(TestSupport.validDiagnosis()));
            new GeminiRestVisionClient(TestSupport.settings(g.baseUrl(), 5, "low"), () -> Optional.of(KEY))
                    .analyze(IMAGE, "image/jpeg");
            JsonNode cfg = TestSupport.MAPPER.readTree(g.requests.get(0).body()).get("generationConfig");
            assertEquals("LOW", cfg.get("thinkingConfig").get("thinkingLevel").textValue());
        }
    }

    // ---------------- configuration ----------------

    @Test
    void missingKeyMeansUnavailableAndNoRequestIsMade() {
        try (MockGemini g = new MockGemini()) {
            GeminiRestVisionClient c = new GeminiRestVisionClient(
                    TestSupport.settings(g.baseUrl(), 5, ""), Optional::empty);
            assertFalse(c.isConfigured());
            FolixAnalysisException e = assertThrows(FolixAnalysisException.class, () -> c.analyze(IMAGE, "image/jpeg"));
            assertEquals(FolixAnalysisException.Code.ANALYSIS_UNAVAILABLE, e.code());
            assertFalse(e.retryable());
            assertEquals(0, g.requests.size());
        }
    }

    @Test
    void unsafeModelNameIsRefusedInsteadOfBeingSplicedIntoTheUrl() {
        try (MockGemini g = new MockGemini()) {
            GeminiSettings bad = new GeminiSettings(g.baseUrl(), "x/../../evil?key=1",
                    java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(2), 4096, "");
            GeminiRestVisionClient c = new GeminiRestVisionClient(bad, () -> Optional.of(KEY));
            assertFalse(c.isConfigured());
            assertEquals(FolixAnalysisException.Code.ANALYSIS_UNAVAILABLE,
                    assertThrows(FolixAnalysisException.class, () -> c.analyze(IMAGE, "image/jpeg")).code());
            assertEquals(0, g.requests.size());
        }
    }

    // ---------------- Gemini failures: each is controlled, each makes ONE request ----------------

    @Test
    void rateLimitIsReportedNotRetriedAndNeverSwitchesKeys() {
        try (MockGemini g = new MockGemini()) {
            g.respond(429, "{\"error\":{\"code\":429,\"message\":\"Quota exceeded\",\"status\":\"RESOURCE_EXHAUSTED\"}}");
            g.responseHeaders = Map.of("Retry-After", "17");
            FolixAnalysisException e = failure(g);
            assertEquals(FolixAnalysisException.Code.RATE_LIMITED, e.code());
            assertTrue(e.retryable());
            assertEquals(17, e.retryAfterSeconds());
            assertEquals(1, g.requests.size(), "a 429 must NOT trigger a second request");
            assertNoSecrets(e);
        }
    }

    @Test
    void rateLimitWithoutRetryAfterHeaderStillWorks() {
        try (MockGemini g = new MockGemini()) {
            g.respond(429, "{}");
            FolixAnalysisException e = failure(g);
            assertEquals(FolixAnalysisException.Code.RATE_LIMITED, e.code());
            assertNull(e.retryAfterSeconds());
            assertEquals(1, g.requests.size());
        }
    }

    @Test
    void retryAfterIsCapped() {
        try (MockGemini g = new MockGemini()) {
            g.respond(429, "{}");
            g.responseHeaders = Map.of("Retry-After", "86400");
            assertEquals(300, failure(g).retryAfterSeconds());
        }
    }

    @Test
    void authenticationAndPermissionFailuresAreOperatorProblemsNotRetryable() {
        for (int status : new int[]{401, 402, 403, 404}) {
            try (MockGemini g = new MockGemini()) {
                g.respond(status, "{\"error\":{\"message\":\"nope\"}}");
                FolixAnalysisException e = failure(g);
                assertEquals(FolixAnalysisException.Code.ANALYSIS_UNAVAILABLE, e.code(), "status " + status);
                assertFalse(e.retryable());
                assertEquals(1, g.requests.size());
                assertNoSecrets(e);
            }
        }
    }

    @Test
    void invalidApiKeyReportedAs400IsAlsoUnavailable() {
        try (MockGemini g = new MockGemini()) {
            g.respond(400, "{\"error\":{\"code\":400,\"message\":\"API key not valid.\",\"status\":\"INVALID_ARGUMENT\","
                    + "\"details\":[{\"reason\":\"API_KEY_INVALID\"}]}}");
            FolixAnalysisException e = failure(g);
            assertEquals(FolixAnalysisException.Code.ANALYSIS_UNAVAILABLE, e.code());
            assertFalse(e.retryable());
        }
    }

    @Test
    void otherBadRequestsAreFailedAndNotRetryable() {
        try (MockGemini g = new MockGemini()) {
            g.respond(400, "{\"error\":{\"status\":\"INVALID_ARGUMENT\",\"message\":\"Unable to process input image\"}}");
            FolixAnalysisException e = failure(g);
            assertEquals(FolixAnalysisException.Code.ANALYSIS_FAILED, e.code());
            assertFalse(e.retryable());
            assertEquals(1, g.requests.size());
        }
    }

    @Test
    void serverErrorsAreRetryableFailuresWithoutInternalRetry() {
        for (int status : new int[]{500, 502, 503}) {
            try (MockGemini g = new MockGemini()) {
                g.respond(status, "{\"error\":{\"status\":\"UNAVAILABLE\"}}");
                FolixAnalysisException e = failure(g);
                assertEquals(FolixAnalysisException.Code.ANALYSIS_FAILED, e.code(), "status " + status);
                assertTrue(e.retryable());
                assertEquals(1, g.requests.size(), "no automatic retry on " + status);
            }
        }
    }

    @Test
    void gateway504IsATimeout() {
        try (MockGemini g = new MockGemini()) {
            g.respond(504, "{}");
            FolixAnalysisException e = failure(g);
            assertEquals(FolixAnalysisException.Code.ANALYSIS_TIMEOUT, e.code());
            assertTrue(e.retryable());
        }
    }

    @Test
    void clientSideTimeoutIsControlledAndMakesOneRequest() {
        try (MockGemini g = new MockGemini()) {
            g.respond(200, TestSupport.geminiEnvelope(TestSupport.validDiagnosis()));
            g.delayMillis = 3000;
            GeminiRestVisionClient c = new GeminiRestVisionClient(
                    TestSupport.settings(g.baseUrl(), 1, ""), () -> Optional.of(KEY));
            long t0 = System.nanoTime();
            FolixAnalysisException e = assertThrows(FolixAnalysisException.class, () -> c.analyze(IMAGE, "image/jpeg"));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertEquals(FolixAnalysisException.Code.ANALYSIS_TIMEOUT, e.code());
            assertTrue(e.retryable());
            assertTrue(ms < 2800, "must give up at the configured timeout, took " + ms + "ms");
            assertEquals(1, g.requests.size());
        }
    }

    @Test
    void unreachableHostIsAControlledRetryableFailure() {
        int deadPort;
        try (MockGemini g = new MockGemini()) {
            deadPort = g.server.getAddress().getPort();
        }
        GeminiRestVisionClient c = new GeminiRestVisionClient(
                TestSupport.settings("http://127.0.0.1:" + deadPort + "/v1beta", 3, ""), () -> Optional.of(KEY));
        FolixAnalysisException e = assertThrows(FolixAnalysisException.class, () -> c.analyze(IMAGE, "image/jpeg"));
        assertEquals(FolixAnalysisException.Code.ANALYSIS_FAILED, e.code());
        assertTrue(e.retryable());
        assertNoSecrets(e);
    }

    // ---------------- malformed / blocked responses ----------------

    @Test
    void malformedSuccessBodiesAreControlledFailures() {
        String[] bodies = {
                "not json at all", "[]", "{}", "{\"candidates\":[]}",
                "{\"candidates\":[{\"content\":{\"parts\":[]},\"finishReason\":\"STOP\"}]}",
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"   \"}]},\"finishReason\":\"STOP\"}]}",
                "{\"candidates\":[{\"finishReason\":\"STOP\"}]}"
        };
        for (String b : bodies) {
            try (MockGemini g = new MockGemini()) {
                g.respond(200, b);
                FolixAnalysisException e = failure(g);
                assertEquals(FolixAnalysisException.Code.ANALYSIS_FAILED, e.code(), b);
                assertEquals(1, g.requests.size());
            }
        }
    }

    @Test
    void truncatedOutputIsRetryableButBlockedOutputIsNot() {
        try (MockGemini g = new MockGemini()) {
            g.respond(200, TestSupport.geminiEnvelope("{\"plant_detected\": tr", "MAX_TOKENS"));
            FolixAnalysisException e = failure(g);
            assertEquals(FolixAnalysisException.Code.ANALYSIS_FAILED, e.code());
            assertTrue(e.retryable());
        }
        try (MockGemini g = new MockGemini()) {
            g.respond(200, TestSupport.geminiEnvelope("", "SAFETY"));
            FolixAnalysisException e = failure(g);
            assertFalse(e.retryable());
        }
        try (MockGemini g = new MockGemini()) {
            g.respond(200, "{\"promptFeedback\":{\"blockReason\":\"PROHIBITED_CONTENT\"}}");
            FolixAnalysisException e = failure(g);
            assertFalse(e.retryable());
        }
    }

    @Test
    void thoughtPartsAreNeverMistakenForTheAnswer() {
        try (MockGemini g = new MockGemini()) {
            String answer = TestSupport.json(TestSupport.validDiagnosis());
            String body = "{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":["
                    + "{\"thought\":true,\"text\":\"internal reasoning that must not be used\"},"
                    + "{\"text\":" + TestSupport.json(answer) + "}]}}]}";
            g.respond(200, body);
            assertEquals(answer, client(g).analyze(IMAGE, "image/jpeg"));
        }
    }

    @Test
    void redirectsAreNeverFollowedSoTheKeyHeaderCannotBeReplayedElsewhere() {
        try (MockGemini target = new MockGemini(); MockGemini g = new MockGemini()) {
            g.respond(307, "{}");
            g.responseHeaders = Map.of("Location", target.baseUrl() + "/steal");
            FolixAnalysisException e = failure(g);
            assertEquals(0, target.requests.size());
            assertEquals(FolixAnalysisException.Code.ANALYSIS_FAILED, e.code());
        }
    }
}
