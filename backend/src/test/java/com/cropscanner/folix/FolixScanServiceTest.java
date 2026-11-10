package com.cropscanner.folix;

import com.cropscanner.folix.TestSupport.FakePlantNet;
import com.cropscanner.folix.TestSupport.FakeVision;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FolixScanServiceTest {

    private static boolean contains(byte[] h, String needle) {
        return new String(h, StandardCharsets.ISO_8859_1).contains(needle);
    }

    private static FolixAnalysisException expectFailure(FolixScanService s, byte[] upload) {
        return assertThrows(FolixAnalysisException.class, () -> s.analyze(upload));
    }

    @Test
    void successfulStructuredDiagnosisMakesExactlyOneVisionCall() {
        FakeVision v = new FakeVision().returning(TestSupport.json(TestSupport.validDiagnosis()));
        DiagnosisResult r = TestSupport.service(v).analyze(TestImages.jpeg_exif());
        assertEquals(1, v.calls);
        assertEquals("Early blight", r.disease());
        assertEquals("high", r.toContractMap().get("confidence"));
    }

    @Test
    void geminiReceivesTheSanitizedImageNotTheOriginal() {
        FakeVision v = new FakeVision().returning(TestSupport.json(TestSupport.validDiagnosis()));
        TestSupport.service(v).analyze(TestImages.jpeg_exif());
        assertEquals("image/jpeg", v.lastMime);
        assertFalse(contains(v.lastBytes, TestImages.MARKER), "GPS/EXIF marker must not reach Gemini");
        assertFalse(contains(v.lastBytes, "SECRET-PHONE-MAKE"));
    }

    @Test
    void nonPlantImageYieldsTheNoDiagnosisShape() {
        FakeVision v = new FakeVision().returning(TestSupport.json(TestSupport.nonPlant()));
        Map<String, Object> m = TestSupport.service(v).analyze(TestImages.png_plain()).toContractMap();
        assertEquals(false, m.get("plant_detected"));
        assertEquals("unknown", m.get("disease"));
        assertEquals("low", m.get("confidence"));
        assertEquals(true, m.get("needs_human_review"));
    }

    @Test
    void uncertainImageKeepsItsUncertainty() {
        Map<String, Object> uncertain = TestSupport.validDiagnosis();
        uncertain.put("disease", "unknown");
        uncertain.put("confidence", "low");
        uncertain.put("severity", "unknown");
        uncertain.put("needs_human_review", true);
        uncertain.put("reasoning", "The photo is too distant and blurred to distinguish lesions from shadow.");
        FakeVision v = new FakeVision().returning(TestSupport.json(uncertain));
        DiagnosisResult r = TestSupport.service(v).analyze(TestImages.jpeg_plain());
        assertTrue(r.plantDetected());
        assertTrue(r.diseaseIsUnknown());
        assertEquals(DiagnosisResult.Confidence.LOW, r.confidence());
        assertTrue(r.needsHumanReview());
    }

    @Test
    void malformedModelOutputIsAControlledErrorNotADiagnosisAndIsNotRetriedAutomatically() {
        FakeVision v = new FakeVision().returning("Sure! It looks like late blight to me.");
        FolixAnalysisException e = expectFailure(TestSupport.service(v), TestImages.jpeg_plain());
        assertEquals(FolixAnalysisException.Code.ANALYSIS_FAILED, e.code());
        assertEquals(1, v.calls, "malformed output must not trigger a second Gemini request");
    }

    @Test
    void missingGeminiConfigurationFailsBeforeAnyWorkOrRequest() {
        FakeVision v = new FakeVision();
        v.configured = false;
        FolixAnalysisException e = expectFailure(TestSupport.service(v), TestImages.jpeg_plain());
        assertEquals(FolixAnalysisException.Code.ANALYSIS_UNAVAILABLE, e.code());
        assertFalse(e.retryable());
        assertEquals(0, v.calls);
    }

    @Test
    void notConfiguredIsReportedEvenForAnInvalidUploadSoItIsNeverBlamedOnTheUser() {
        FakeVision v = new FakeVision();
        v.configured = false;
        assertEquals(FolixAnalysisException.Code.ANALYSIS_UNAVAILABLE,
                expectFailure(TestSupport.service(v), "junk".getBytes(StandardCharsets.UTF_8)).code());
    }

    @Test
    void invalidUploadsNeverReachGemini() {
        FakeVision v = new FakeVision().returning("{}");
        FolixScanService s = TestSupport.service(v);
        assertEquals(FolixAnalysisException.Code.UNSUPPORTED_IMAGE_TYPE,
                expectFailure(s, "hello".getBytes(StandardCharsets.UTF_8)).code());
        assertEquals(FolixAnalysisException.Code.INVALID_IMAGE, expectFailure(s, new byte[0]).code());
        assertEquals(0, v.calls);
    }

    @Test
    void oversizedUploadNeverReachesGemini() {
        FakeVision v = new FakeVision().returning("{}");
        FolixScanService s = new FolixScanService(new ImageSanitizer(500), v, new AssessmentParser(new ActionSafetyPolicy()));
        byte[] big = new byte[501];
        big[0] = (byte) 0xFF; big[1] = (byte) 0xD8; big[2] = (byte) 0xFF;
        assertEquals(FolixAnalysisException.Code.IMAGE_TOO_LARGE, expectFailure(s, big).code());
        assertEquals(0, v.calls);
    }

    @Test
    void geminiRateLimitPropagatesAsRateLimitedAfterOneCall() {
        FakeVision v = new FakeVision().failing(FolixAnalysisException.rateLimited(30, "429"));
        FolixAnalysisException e = expectFailure(TestSupport.service(v), TestImages.jpeg_plain());
        assertEquals(FolixAnalysisException.Code.RATE_LIMITED, e.code());
        assertEquals(30, e.retryAfterSeconds());
        assertEquals(1, v.calls);
    }

    // ---------------- Gemini -> Pl@ntNet online fallback ----------------

    @Test
    void geminiFailureFallsBackToPlantNetWhenConfigured() {
        FakeVision gemini = new FakeVision().failing(FolixAnalysisException.failed("Gemini HTTP 503", true));
        FakePlantNet plantNet = new FakePlantNet().returning(TestSupport.plantNetResponse(0.9, "Coccinella septempunctata", "Ladybird"));

        DiagnosisResult r = TestSupport.serviceWithPlantNet(gemini, plantNet).analyze(TestImages.jpeg_plain());

        assertEquals(1, gemini.calls);
        assertEquals(1, plantNet.calls);
        assertTrue(r.plantDetected());
        // BUGFIX (Issue #5): Pl@ntNet identifies species, never a disease — disease must stay
        // "unknown" and confidence must stay LOW no matter how confident the species match was.
        assertEquals(DiagnosisResult.UNKNOWN, r.disease());
        assertEquals(DiagnosisResult.Confidence.LOW, r.confidence());
        assertTrue(r.needsHumanReview());
        assertEquals("Ladybird", r.crop());
        assertTrue(r.symptoms().isEmpty());
    }

    @Test
    void geminiAndPlantNetBothFailingSurfacesTheOriginalGeminiError() {
        FolixAnalysisException geminiError = FolixAnalysisException.rateLimited(30, "Gemini HTTP 429");
        FakeVision gemini = new FakeVision().failing(geminiError);
        FakePlantNet plantNet = new FakePlantNet().failing(FolixAnalysisException.unavailable("Pl@ntNet HTTP 401"));

        FolixAnalysisException thrown = expectFailure(TestSupport.serviceWithPlantNet(gemini, plantNet), TestImages.jpeg_plain());

        assertSame(geminiError, thrown, "the ORIGINAL Gemini exception must surface unchanged");
        assertEquals(FolixAnalysisException.Code.RATE_LIMITED, thrown.code());
        assertEquals(1, gemini.calls);
        assertEquals(1, plantNet.calls);
    }

    @Test
    void plantNetEmptyResultsCountsAsPlantNetFailureAndOriginalGeminiErrorSurfaces() {
        FolixAnalysisException geminiError = FolixAnalysisException.failed("Gemini HTTP 500", true);
        FakeVision gemini = new FakeVision().failing(geminiError);
        FakePlantNet plantNet = new FakePlantNet().returning(TestSupport.plantNetEmptyResponse());

        FolixAnalysisException thrown = expectFailure(TestSupport.serviceWithPlantNet(gemini, plantNet), TestImages.jpeg_plain());

        assertSame(geminiError, thrown);
        assertEquals(1, plantNet.calls);
    }

    @Test
    void plantNetNotConfiguredSurfacesTheOriginalGeminiErrorWithoutCallingPlantNet() {
        FolixAnalysisException geminiError = FolixAnalysisException.unavailable("no Gemini key");
        FakeVision gemini = new FakeVision().failing(geminiError);
        FakePlantNet plantNet = new FakePlantNet();
        plantNet.configured = false;

        FolixAnalysisException thrown = expectFailure(TestSupport.serviceWithPlantNet(gemini, plantNet), TestImages.jpeg_plain());

        assertSame(geminiError, thrown);
        assertEquals(0, plantNet.calls, "an unconfigured fallback must never be called");
    }

    @Test
    void noPlantNetBeanAtAllBehavesExactlyAsBeforeTheFallbackExisted() {
        FolixAnalysisException geminiError = FolixAnalysisException.failed("Gemini HTTP 502", true);
        FakeVision gemini = new FakeVision().failing(geminiError);

        FolixAnalysisException thrown = expectFailure(TestSupport.serviceWithPlantNet(gemini, null), TestImages.jpeg_plain());

        assertSame(geminiError, thrown);
        assertEquals(1, gemini.calls);
    }

    @Test
    void successfulGeminiCallNeverTouchesPlantNetAtAll() {
        FakeVision gemini = new FakeVision().returning(TestSupport.json(TestSupport.validDiagnosis()));
        FakePlantNet plantNet = new FakePlantNet().returning(TestSupport.plantNetResponse(0.9, "should never be used", "should never be used"));

        DiagnosisResult r = TestSupport.serviceWithPlantNet(gemini, plantNet).analyze(TestImages.jpeg_plain());

        assertEquals("Early blight", r.disease());
        assertEquals(0, plantNet.calls, "Pl@ntNet must only be tried after Gemini fails");
    }

    @Test
    void geminiAuthAndServerFailuresPropagateUnchanged() {
        FakeVision auth = new FakeVision().failing(FolixAnalysisException.unavailable("401"));
        assertEquals(FolixAnalysisException.Code.ANALYSIS_UNAVAILABLE,
                expectFailure(TestSupport.service(auth), TestImages.jpeg_plain()).code());
        FakeVision down = new FakeVision().failing(FolixAnalysisException.failed("HTTP 503", true));
        assertEquals(FolixAnalysisException.Code.ANALYSIS_FAILED,
                expectFailure(TestSupport.service(down), TestImages.jpeg_plain()).code());
        assertEquals(1, auth.calls);
        assertEquals(1, down.calls);
    }
}
