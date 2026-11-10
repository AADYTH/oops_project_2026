package com.cropscanner.folix;

import com.cropscanner.folix.TestSupport.FakeVision;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * HTTP-level check of the response contract with the REAL Spring MVC + Jackson stack.
 * (Run with {@code mvn test}; see the Stage 4 notes — this class needs Spring's test jars.)
 */
class FolixScanControllerTest {

    private MockMvc mvc(FakeVision vision) {
        return MockMvcBuilders.standaloneSetup(new FolixScanController(TestSupport.service(vision)))
                .setControllerAdvice(new FolixExceptionHandler())
                .build();
    }

    private static MockMultipartFile image() {
        return new MockMultipartFile("image", "leaf.jpg", "image/jpeg", TestImages.jpeg_exif());
    }

    @Test
    void successReturnsTheExactContractAsJsonAndIsNeverCached() throws Exception {
        FakeVision v = new FakeVision().returning(TestSupport.json(TestSupport.validDiagnosis()));
        mvc(v).perform(multipart("/api/scan/analyze").file(image()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.plant_detected").value(true))
                .andExpect(jsonPath("$.plant_part").value("leaf"))
                .andExpect(jsonPath("$.crop").value("Tomato"))
                .andExpect(jsonPath("$.disease").value("Early blight"))
                .andExpect(jsonPath("$.severity").value("moderate"))
                .andExpect(jsonPath("$.confidence").value("high"))
                .andExpect(jsonPath("$.symptoms.length()").value(2))
                .andExpect(jsonPath("$.reasoning").isString())
                .andExpect(jsonPath("$.recommended_actions").isArray())
                .andExpect(jsonPath("$.needs_human_review").value(false));
    }

    @Test
    void missingImagePartIsA400WithTheCleanErrorStructure() throws Exception {
        mvc(new FakeVision()).perform(multipart("/api/scan/analyze"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_IMAGE"))
                .andExpect(jsonPath("$.error.retryable").value(false));
    }

    @Test
    void nonMultipartRequestIsTreatedAsMissingImageNotAServerError() throws Exception {
        mvc(new FakeVision()).perform(post("/api/scan/analyze").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_IMAGE"));
    }

    @Test
    void rateLimitBecomes429WithRetryAfterAndNoInternals() throws Exception {
        FakeVision v = new FakeVision().failing(FolixAnalysisException.rateLimited(12, "Gemini rate limit (HTTP 429)"));
        mvc(v).perform(multipart("/api/scan/analyze").file(image()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "12"))
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.error.retryable").value(true))
                .andExpect(jsonPath("$.error.message").isString())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(not(containsString("Gemini"))));
    }

    @Test
    void malformedModelOutputBecomes502NotADiagnosis() throws Exception {
        FakeVision v = new FakeVision().returning("definitely not json");
        mvc(v).perform(multipart("/api/scan/analyze").file(image()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("ANALYSIS_FAILED"))
                .andExpect(jsonPath("$.plant_detected").doesNotExist())
                .andExpect(jsonPath("$.disease").doesNotExist());
    }

    @Test
    void unsupportedFileIs415() throws Exception {
        MockMultipartFile text = new MockMultipartFile("image", "notes.txt", "image/jpeg", "hello".getBytes());
        mvc(new FakeVision().returning("{}")).perform(multipart("/api/scan/analyze").file(text))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_IMAGE_TYPE"));
    }

    @Test
    void notConfiguredIs503() throws Exception {
        FakeVision v = new FakeVision();
        v.configured = false;
        mvc(v).perform(multipart("/api/scan/analyze").file(image()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("ANALYSIS_UNAVAILABLE"));
    }
}
