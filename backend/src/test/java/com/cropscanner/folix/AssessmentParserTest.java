package com.cropscanner.folix;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AssessmentParserTest {

    private final AssessmentParser parser = new AssessmentParser(new ActionSafetyPolicy());

    private DiagnosisResult parse(Map<String, Object> m) {
        return parser.parse(TestSupport.json(m));
    }

    private void assertMalformed(String text) {
        FolixAnalysisException e = assertThrows(FolixAnalysisException.class, () -> parser.parse(text));
        assertEquals(FolixAnalysisException.Code.ANALYSIS_FAILED, e.code());
        assertTrue(e.retryable());
    }

    // ---------------- success paths ----------------

    @Test
    void validCropImageBecomesAConfidentDiagnosis() {
        DiagnosisResult r = parse(TestSupport.validDiagnosis());
        assertTrue(r.plantDetected());
        assertEquals(DiagnosisResult.PlantPart.LEAF, r.plantPart());
        assertEquals("Tomato", r.crop());
        assertEquals("Early blight", r.disease());
        assertEquals(DiagnosisResult.Severity.MODERATE, r.severity());
        assertEquals(DiagnosisResult.Confidence.HIGH, r.confidence());
        assertFalse(r.needsHumanReview());
        assertEquals(2, r.symptoms().size());
    }

    @Test
    void contractMapHasExactlyTheDocumentedKeysInOrder() {
        Map<String, Object> m = parse(TestSupport.validDiagnosis()).toContractMap();
        assertEquals(List.of("plant_detected", "plant_part", "crop", "disease", "severity", "confidence",
                "symptoms", "reasoning", "recommended_actions", "needs_human_review", "remedy_plan", "epidemic_risk", "severity_percent", "spread_chance_percent"), new ArrayList<>(m.keySet()));
        assertEquals(Boolean.TRUE, m.get("plant_detected"));
        assertEquals("leaf", m.get("plant_part"));
        assertEquals("moderate", m.get("severity"));
        assertEquals("high", m.get("confidence"));
        assertEquals(Boolean.FALSE, m.get("needs_human_review"));
    }

    @Test
    void nonPlantImageForcesEveryFieldToTheUncertainShape() {
        DiagnosisResult r = parse(TestSupport.nonPlant());
        assertFalse(r.plantDetected());
        assertEquals(DiagnosisResult.PlantPart.UNKNOWN, r.plantPart());
        assertEquals("unknown", r.disease());
        assertEquals(DiagnosisResult.Severity.UNKNOWN, r.severity());
        assertEquals(DiagnosisResult.Confidence.LOW, r.confidence());
        assertTrue(r.needsHumanReview());
        assertTrue(r.symptoms().isEmpty());
        assertTrue(r.recommendedActions().isEmpty());
        assertFalse(r.reasoning().isBlank(), "a useful explanation must survive");
    }

    @Test
    void modelCannotSmuggleADiagnosisIntoANonPlantResult() {
        Map<String, Object> m = TestSupport.validDiagnosis();
        m.put("plant_detected", false);            // says "no plant" but still fills in a confident disease
        DiagnosisResult r = parse(m);
        assertEquals("unknown", r.disease());
        assertEquals("unknown", r.crop());
        assertEquals(DiagnosisResult.Confidence.LOW, r.confidence());
        assertEquals(DiagnosisResult.Severity.UNKNOWN, r.severity());
        assertTrue(r.needsHumanReview());
        assertTrue(r.symptoms().isEmpty());
    }

    // ---------------- uncertainty is preserved and only ever increases ----------------

    @Test
    void unknownDiseaseCannotCarryConfidenceAndAlwaysNeedsReview() {
        Map<String, Object> m = TestSupport.validDiagnosis();
        m.put("disease", "unknown");
        m.put("confidence", "high");          // contradictory
        m.put("needs_human_review", false);   // contradictory
        DiagnosisResult r = parse(m);
        assertEquals("unknown", r.disease());
        assertEquals(DiagnosisResult.Confidence.LOW, r.confidence());
        assertTrue(r.needsHumanReview());
    }

    @Test
    void lowConfidenceAlwaysMeansHumanReview() {
        Map<String, Object> m = TestSupport.validDiagnosis();
        m.put("confidence", "low");
        m.put("needs_human_review", false);
        assertTrue(parse(m).needsHumanReview());
    }

    @Test
    void modelRequestedReviewIsNeverRemoved() {
        Map<String, Object> m = TestSupport.validDiagnosis();
        m.put("needs_human_review", true);
        assertTrue(parse(m).needsHumanReview());
    }

    @Test
    void aliasesForNoAnswerBecomeTheLiteralUnknown() {
        for (String alias : List.of("Unknown", " N/A ", "none", "Unclear", "Cannot determine", "", "   ")) {
            Map<String, Object> m = TestSupport.validDiagnosis();
            m.put("disease", alias);
            DiagnosisResult r = parse(m);
            assertEquals("unknown", r.disease(), "alias: '" + alias + "'");
            assertTrue(r.needsHumanReview());
        }
    }

    @Test
    void healthyIsAValidConditionName() {
        Map<String, Object> m = TestSupport.validDiagnosis();
        m.put("disease", "healthy");
        m.put("severity", "unknown");
        DiagnosisResult r = parse(m);
        assertEquals("Healthy", r.disease());
        assertFalse(r.diseaseIsUnknown());
    }

    // ---------------- descriptive enums degrade safely ----------------

    @Test
    void unexpectedPlantPartAndSeverityBecomeUnknown() {
        Map<String, Object> m = TestSupport.validDiagnosis();
        m.put("plant_part", "root");
        m.put("severity", "catastrophic");
        DiagnosisResult r = parse(m);
        assertEquals(DiagnosisResult.PlantPart.UNKNOWN, r.plantPart());
        assertEquals(DiagnosisResult.Severity.UNKNOWN, r.severity());
    }

    @Test
    void enumSpellingVariantsAreNormalized() {
        Map<String, Object> m = TestSupport.validDiagnosis();
        m.put("plant_part", "Whole Plant");
        m.put("severity", " MODERATE ");
        m.put("confidence", "High");
        DiagnosisResult r = parse(m);
        assertEquals(DiagnosisResult.PlantPart.WHOLE_PLANT, r.plantPart());
        assertEquals(DiagnosisResult.Severity.MODERATE, r.severity());
        assertEquals(DiagnosisResult.Confidence.HIGH, r.confidence());
    }

    // ---------------- malformed output is an error, never a diagnosis ----------------

    @Test
    void unexpectedConfidenceValueIsAnErrorNotAGuess() {
        Map<String, Object> m = TestSupport.validDiagnosis();
        m.put("confidence", "very high");
        assertMalformed(TestSupport.json(m));
        m.put("confidence", 0.93);
        assertMalformed(TestSupport.json(m));
    }

    @Test
    void notJsonEmptyOrWrongRootTypeIsMalformed() {
        assertMalformed(null);
        assertMalformed("");
        assertMalformed("   ");
        assertMalformed("I think this is early blight.");
        assertMalformed("{\"plant_detected\": true, ");            // truncated
        assertMalformed("[{\"plant_detected\": true}]");            // array root
        assertMalformed("\"just a string\"");
        assertMalformed("42");
    }

    @Test
    void everyRequiredFieldMustBePresent() {
        for (String field : TestSupport.validDiagnosis().keySet()) {
            Map<String, Object> m = TestSupport.validDiagnosis();
            m.remove(field);
            assertMalformed(TestSupport.json(m));
        }
    }

    @Test
    void nullValuesInRequiredFieldsAreMalformed() {
        for (String field : TestSupport.validDiagnosis().keySet()) {
            Map<String, Object> m = TestSupport.validDiagnosis();
            m.put(field, null);
            assertMalformed(TestSupport.json(m));
        }
    }

    @Test
    void wrongTypesAreMalformedNotCoerced() {
        Map<String, Object> m = TestSupport.validDiagnosis();
        m.put("plant_detected", "true");                            // string, not boolean
        assertMalformed(TestSupport.json(m));

        m = TestSupport.validDiagnosis();
        m.put("needs_human_review", "false");
        assertMalformed(TestSupport.json(m));

        m = TestSupport.validDiagnosis();
        m.put("symptoms", "brown spots");                           // string, not array
        assertMalformed(TestSupport.json(m));

        m = TestSupport.validDiagnosis();
        m.put("recommended_actions", Map.of("a", "b"));
        assertMalformed(TestSupport.json(m));

        m = TestSupport.validDiagnosis();
        m.put("crop", List.of("tomato"));
        assertMalformed(TestSupport.json(m));
    }

    @Test
    void blankReasoningIsMalformedBecauseAnUnexplainedConclusionIsUseless() {
        Map<String, Object> m = TestSupport.validDiagnosis();
        m.put("reasoning", "   ");
        assertMalformed(TestSupport.json(m));
    }

    // ---------------- tolerated harmless variations ----------------

    @Test
    void markdownFencesAndUnknownExtraFieldsAreTolerated() {
        Map<String, Object> m = TestSupport.validDiagnosis();
        m.put("model_notes", "extra field the contract does not have");
        DiagnosisResult r = parser.parse("```json\n" + TestSupport.json(m) + "\n```");
        assertEquals("Early blight", r.disease());
        assertFalse(r.toContractMap().containsKey("model_notes"), "unknown fields must not leak to the browser");
    }

    @Test
    void arraysDropBlankNonStringAndDuplicateEntriesAndAreCapped() {
        Map<String, Object> m = TestSupport.validDiagnosis();
        List<Object> symptoms = new ArrayList<>(Arrays.asList("Spots", "spots", "  ", 7, null, "Yellowing"));
        for (int i = 0; i < 20; i++) symptoms.add("Symptom number " + i);
        m.put("symptoms", symptoms);
        DiagnosisResult r = parse(m);
        assertEquals("Spots", r.symptoms().get(0));
        assertEquals("Yellowing", r.symptoms().get(1));
        assertEquals(AssessmentParser.MAX_SYMPTOMS, r.symptoms().size());
    }

    @Test
    void overlongTextIsTruncated() {
        Map<String, Object> m = TestSupport.validDiagnosis();
        m.put("reasoning", "word ".repeat(2000));
        m.put("disease", "x".repeat(500));
        DiagnosisResult r = parse(m);
        assertTrue(r.reasoning().length() <= AssessmentParser.MAX_REASONING + 1);
        assertTrue(r.disease().length() <= AssessmentParser.MAX_DISEASE + 1);
    }

    // ---------------- recommended actions are made safe ----------------

    @Test
    void fabricatedDosagesScheduleAndLegalClaimsAreDroppedAndLocalGuidanceIsAdded() {
        Map<String, Object> m = TestSupport.validDiagnosis();
        m.put("recommended_actions", List.of(
                "Spray mancozeb at 2.5 g per litre of water every 7 days.",
                "Apply 3 ml fungicide per plant twice a week.",
                "Copper treatment is legally required for this disease.",
                "Remove the affected leaves and bin them.",
                "Use a concentration of 0.2% for best results."));
        DiagnosisResult r = parse(m);
        assertEquals(2, r.recommendedActions().size(), r.recommendedActions().toString());
        assertEquals("Remove the affected leaves and bin them.", r.recommendedActions().get(0));
        assertEquals(ActionSafetyPolicy.LOCAL_GUIDANCE_ACTION, r.recommendedActions().get(1));
        String all = String.join(" ", r.recommendedActions()).toLowerCase();
        assertFalse(all.contains("mancozeb") || all.contains("2.5") || all.contains("legally") || all.contains("0.2%"));
    }

    @Test
    void productMentionsWithoutDosesAreKeptButLabelledAsAiGuidance() {
        Map<String, Object> m = TestSupport.validDiagnosis();
        m.put("recommended_actions", List.of("A copper-based fungicide may help; ask a local agricultural officer."));
        DiagnosisResult r = parse(m);
        assertTrue(r.recommendedActions().get(0).startsWith(ActionSafetyPolicy.AI_GUIDANCE_PREFIX));
    }

    @Test
    void ordinaryHusbandryAdviceIsNotFlaggedAsADose() {
        Map<String, Object> m = TestSupport.validDiagnosis();
        List<String> ok = List.of(
                "Inspect the plants every 3 days for new spots.",
                "Keep humidity below 70% by improving air flow.",
                "Space plants about 30 cm apart.",
                "Water at the base in the morning.");
        m.put("recommended_actions", ok);
        DiagnosisResult r = parse(m);
        assertTrue(r.recommendedActions().containsAll(ok), r.recommendedActions().toString());
    }

    @Test
    void allActionsDroppedStillLeavesTheSafeLocalGuidancePointer() {
        Map<String, Object> m = TestSupport.validDiagnosis();
        m.put("recommended_actions", List.of("Dose: 5 ml per litre."));
        assertEquals(List.of(ActionSafetyPolicy.LOCAL_GUIDANCE_ACTION), parse(m).recommendedActions());
    }
}
