package com.cropscanner.folix;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BUGFIX (Issue #5): these tests used to assert that a Pl@ntNet species match score became a
 * "disease" name and a disease confidence level. Pl@ntNet identifies plant SPECIES, not diseases
 * (see PlantNetDiagnosisMapper's class doc and PlantNetSpeciesClient's, which cites Pl@ntNet's real
 * API docs at my.plantnet.org/doc/api/identify). These tests now assert the corrected behavior:
 * disease is always "unknown" and confidence is always LOW for this fallback, no matter how
 * confident Pl@ntNet is about the species — because a species match is never a disease diagnosis.
 */
class PlantNetDiagnosisMapperTest {

    private final PlantNetDiagnosisMapper mapper = new PlantNetDiagnosisMapper();

    @Test
    void aConfidentSpeciesMatchIsNeverReportedAsADiseaseOrGivenDiseaseConfidence() {
        DiagnosisResult r = mapper.map(TestSupport.plantNetResponse(0.95, "Solanum lycopersicum", "Tomato"));
        assertEquals(DiagnosisResult.UNKNOWN, r.disease(), "a species match is never a disease diagnosis");
        assertEquals(DiagnosisResult.Confidence.LOW, r.confidence(), "species-match score must not leak in as disease confidence");
        assertTrue(r.plantDetected());
        assertTrue(r.needsHumanReview());
        assertEquals("Tomato", r.crop());
        assertTrue(r.symptoms().isEmpty());
        assertTrue(r.reasoning().contains("Tomato"));
        assertTrue(r.reasoning().toLowerCase().contains("does not diagnose diseases"));
    }

    @Test
    void lowScoreStillNeverBecomesADiseaseOrRaisesConfidence() {
        DiagnosisResult r = mapper.map(TestSupport.plantNetResponse(0.1, "Oryza sativa", "Rice"));
        assertEquals(DiagnosisResult.UNKNOWN, r.disease());
        assertEquals(DiagnosisResult.Confidence.LOW, r.confidence());
        assertEquals("Rice", r.crop());
    }

    @Test
    void fallsBackToTheScientificNameWhenNoCommonNameIsGiven() {
        DiagnosisResult r = mapper.map(TestSupport.plantNetResponse(0.6, "Bellis perennis", null));
        assertEquals("Bellis perennis", r.crop());
        assertEquals(DiagnosisResult.UNKNOWN, r.disease());
    }

    @Test
    void emptyResultsThrowsUnavailableRatherThanFabricatingAnAnswer() {
        FolixAnalysisException e = assertThrows(FolixAnalysisException.class,
                () -> mapper.map(TestSupport.plantNetEmptyResponse()));
        assertEquals(FolixAnalysisException.Code.ANALYSIS_UNAVAILABLE, e.code());
    }

    @Test
    void malformedJsonIsAControlledFailure() {
        FolixAnalysisException e = assertThrows(FolixAnalysisException.class,
                () -> mapper.map("not json at all"));
        assertEquals(FolixAnalysisException.Code.ANALYSIS_FAILED, e.code());
    }

    @Test
    void blankResponseIsAControlledFailure() {
        assertThrows(FolixAnalysisException.class, () -> mapper.map(""));
        assertThrows(FolixAnalysisException.class, () -> mapper.map(null));
    }

    @Test
    void missingSpeciesInfoFallsBackToUnknownCropRatherThanGuessing() {
        String response = "{\"results\":[{\"score\":0.6,\"species\":{}}]}";
        DiagnosisResult r = mapper.map(response);
        assertEquals(DiagnosisResult.UNKNOWN, r.crop());
        assertEquals(DiagnosisResult.UNKNOWN, r.disease());
    }
}
