package com.cropscanner.folix;

import com.cropscanner.folix.DiagnosisResult.Confidence;
import com.cropscanner.folix.DiagnosisResult.PlantPart;
import com.cropscanner.folix.DiagnosisResult.Severity;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Severity % and spread-chance % must stay available when the model's confidence is LOW. */
class SpreadChanceEstimatorTest {

    private static DiagnosisResult diagnosis(String disease, Severity sev, Confidence conf,
                                             boolean epidemic, Integer sevPercent) {
        return new DiagnosisResult(true, PlantPart.LEAF, "Tomato", disease, sev, conf,
                List.of("spot"), "reasoning", List.of("action"), conf == Confidence.LOW,
                null, epidemic, sevPercent, null);
    }

    @Test
    void lowConfidenceDoesNotSuppressTheEstimate() {
        DiagnosisResult low = diagnosis("Late blight", Severity.MODERATE, Confidence.LOW, true, 40);
        DiagnosisResult high = diagnosis("Late blight", Severity.MODERATE, Confidence.HIGH, true, 40);
        assertNotNull(SpreadChanceEstimator.estimate(low));
        assertEquals(SpreadChanceEstimator.estimate(high), SpreadChanceEstimator.estimate(low));
    }

    @Test
    void epidemicDiseaseSpreadsMoreThanOrdinaryAtTheSameSeverity() {
        int epidemic = SpreadChanceEstimator.estimate(diagnosis("Late blight", Severity.MODERATE, Confidence.LOW, true, 40));
        int ordinary = SpreadChanceEstimator.estimate(diagnosis("Early blight", Severity.MODERATE, Confidence.LOW, false, 40));
        assertTrue(epidemic > ordinary);
        assertEquals(55 + 16, epidemic);   // base 55 + 40% * 0.4
        assertEquals(15 + 16, ordinary);
    }

    @Test
    void fallsBackToTheSeverityBandWhenNoPercentageWasGiven() {
        assertEquals(15 + 30, SpreadChanceEstimator.estimate(
                diagnosis("Early blight", Severity.SEVERE, Confidence.MODERATE, false, null))); // 75 * 0.4
    }

    @Test
    void resultIsClampedToTheDocumentedRange() {
        assertEquals(SpreadChanceEstimator.MAX, SpreadChanceEstimator.estimate(
                diagnosis("Late blight", Severity.SEVERE, Confidence.LOW, true, 100)));
    }

    @Test
    void noHonestEstimateForUnknownHealthyOrNoPlant() {
        assertNull(SpreadChanceEstimator.estimate(diagnosis("unknown", Severity.UNKNOWN, Confidence.LOW, false, null)));
        assertNull(SpreadChanceEstimator.estimate(diagnosis("Healthy", Severity.UNKNOWN, Confidence.HIGH, false, 0)));
        DiagnosisResult noPlant = new DiagnosisResult(false, PlantPart.UNKNOWN, "unknown", "unknown",
                Severity.UNKNOWN, Confidence.LOW, List.of(), "not a plant", List.of(), true);
        assertNull(SpreadChanceEstimator.estimate(noPlant));
    }

    @Test
    void parserKeepsSeverityPercentForALowConfidenceNamedDisease() {
        AssessmentParser parser = new AssessmentParser(new ActionSafetyPolicy());
        Map<String, Object> m = new LinkedHashMap<>(TestSupport.validDiagnosis());
        m.put("confidence", "low");
        m.put("severity_percent", 35);
        DiagnosisResult r = parser.parse(TestSupport.json(m));
        assertEquals(Confidence.LOW, r.confidence());
        assertEquals("Early blight", r.disease());
        assertEquals(Integer.valueOf(35), r.severityPercent());
    }

    @Test
    void parserClampsAndToleratesBadSeverityPercent() {
        AssessmentParser parser = new AssessmentParser(new ActionSafetyPolicy());
        Map<String, Object> m = new LinkedHashMap<>(TestSupport.validDiagnosis());
        m.put("severity_percent", 250);
        assertEquals(Integer.valueOf(100), parser.parse(TestSupport.json(m)).severityPercent());
        m.put("severity_percent", "lots");
        assertNull(parser.parse(TestSupport.json(m)).severityPercent());
        m.remove("severity_percent");
        assertNull(parser.parse(TestSupport.json(m)).severityPercent());
    }
}
