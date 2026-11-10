package com.cropscanner.folix;

import com.cropscanner.model.DiseaseInfo;
import com.cropscanner.service.RemedyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * The FOLIX Scanner pipeline:
 *
 *   upload → validate + strip metadata → ONE Gemini request → validate + normalize → result
 *          ↳ (online-only) Gemini fails → ONE Pl@ntNet request → map → result
 *
 * This is the entire diagnosis path. It depends on nothing from the legacy
 * CropGuard scan pipeline (no ONNX/PlantVillage/PlantDoc, no inference-service,
 * no OpenCV scoring, no EvidenceConfidenceService).
 *
 * The Pl@ntNet step is a second-tier ONLINE fallback only: it fires when this
 * backend is reachable but {@link VisionClient#analyze} itself throws. It is
 * completely unrelated to — and must never be confused with — the frontend's
 * separate offline/on-device path ({@code scanner-experience.js} /
 * {@code onnx-classifier.js} / {@code offline-classifier.js}), which handles
 * the case where the browser has no network at all and is untouched by this
 * class. If neither Gemini nor Pl@ntNet can answer, the caller gets a
 * {@link FolixAnalysisException}, never a local guess.
 *
 * Images are held only in memory for the duration of the call and never
 * persisted or logged.
 */
@Service
public class FolixScanService {

    private static final Logger log = LoggerFactory.getLogger(FolixScanService.class);

    private final ImageSanitizer sanitizer;
    private final VisionClient vision;
    private final AssessmentParser parser;
    private final PlantNetSpeciesClient plantNet;
    private final PlantNetDiagnosisMapper plantNetMapper;
    private final RemedyService remedyService;
    private final EpidemicRiskLookup epidemicRiskLookup;

    /**
     * Convenience constructor used by tests that don't exercise the Pl@ntNet fallback path or remedy/danger
     * enrichment. {@code EpidemicRiskLookup} is safe to construct directly outside Spring here: with its
     * {@code @PostConstruct} never run, its lookup table is simply empty, so {@code isEpidemicRisk} always
     * returns the same safe {@code false} default it would for any unmatched disease.
     */
    public FolixScanService(ImageSanitizer sanitizer, VisionClient vision, AssessmentParser parser) {
        this(sanitizer, vision, parser, Optional.empty(), new PlantNetDiagnosisMapper(),
                diseaseName -> Optional.empty(), new EpidemicRiskLookup());
    }

    /** Convenience constructor for tests that need remedy/danger enrichment but not the Pl@ntNet fallback. */
    public FolixScanService(ImageSanitizer sanitizer, VisionClient vision, AssessmentParser parser,
                             RemedyService remedyService, EpidemicRiskLookup epidemicRiskLookup) {
        this(sanitizer, vision, parser, Optional.empty(), new PlantNetDiagnosisMapper(),
                remedyService, epidemicRiskLookup);
    }

    /** Convenience constructor for tests that exercise the Pl@ntNet fallback chain without remedy/danger enrichment. */
    public FolixScanService(ImageSanitizer sanitizer, VisionClient vision, AssessmentParser parser,
                             Optional<PlantNetSpeciesClient> plantNet, PlantNetDiagnosisMapper plantNetMapper) {
        this(sanitizer, vision, parser, plantNet, plantNetMapper,
                diseaseName -> Optional.empty(), new EpidemicRiskLookup());
    }

    @Autowired
    public FolixScanService(ImageSanitizer sanitizer, VisionClient vision, AssessmentParser parser,
                             Optional<PlantNetSpeciesClient> plantNet, PlantNetDiagnosisMapper plantNetMapper,
                             RemedyService remedyService, EpidemicRiskLookup epidemicRiskLookup) {
        this.sanitizer = sanitizer;
        this.vision = vision;
        this.parser = parser;
        this.plantNet = plantNet.orElse(null);
        this.plantNetMapper = plantNetMapper;
        this.remedyService = remedyService;
        this.epidemicRiskLookup = epidemicRiskLookup;
    }

    public DiagnosisResult analyze(byte[] uploadedBytes) {
        // Configuration is checked first so a missing key is never misreported as a problem with the user's photo.
        if (!vision.isConfigured()) {
            throw FolixAnalysisException.unavailable("no Gemini API key configured (set GEMINI_API_KEYS or GEMINI_KEY_1)");
        }

        ImageSanitizer.SanitizedImage image = sanitizer.sanitize(uploadedBytes);

        try {
            String modelText = vision.analyze(image.bytes(), image.mimeType());
            DiagnosisResult result = enrichWithRemedyAndDanger(parser.parse(modelText));
            log.info("FOLIX analysis complete via Gemini: plant_detected={}, confidence={}, needs_human_review={}, "
                            + "has_remedy_plan={}, epidemic_risk={}",
                    result.plantDetected(), result.confidence().wire(), result.needsHumanReview(),
                    result.remedyPlan() != null, result.epidemicRisk());
            return result;
        } catch (FolixAnalysisException geminiFailure) {
            if (plantNet == null || !plantNet.isConfigured()) {
                throw geminiFailure;
            }
            try {
                String plantNetText = plantNet.identify(image.bytes(), image.mimeType());
                DiagnosisResult result = enrichWithRemedyAndDanger(plantNetMapper.map(plantNetText));
                log.info("FOLIX analysis complete via Pl@ntNet fallback (Gemini unavailable): "
                                + "confidence={}, needs_human_review={}",
                        result.confidence().wire(), result.needsHumanReview());
                return result;
            } catch (RuntimeException plantNetFailure) {
                // The fallback itself failed (not configured correctly, also down, bad response, etc.):
                // surface the ORIGINAL Gemini failure unchanged, exactly as before this fallback existed.
                throw geminiFailure;
            }
        }
    }

    /**
     * Looks up remedy guidance and the audited epidemic-risk flag for the diagnosed disease and attaches
     * them to the result. Both the disease name lookup and the epidemic-risk lookup normalize/fuzzy-match
     * against their own curated keys (see {@link com.cropscanner.service.impl.JsonRemedyService} and
     * {@link EpidemicRiskLookup}), since the classifier's naming may not match either exactly. A disease
     * with no knowledge-base entry gets a null {@code remedyPlan} — never a fabricated one — and
     * {@code epidemicRisk=false} — never assumed dangerous.
     */
    private DiagnosisResult enrichWithRemedyAndDanger(DiagnosisResult result) {
        DiseaseInfo.RemedyPlan remedyPlan = result.diseaseIsUnknown() || !result.plantDetected()
                ? null
                : remedyService.findRemedyPlan(result.disease()).orElse(null);
        boolean epidemicRisk = result.plantDetected() && !result.diseaseIsUnknown()
                && epidemicRiskLookup.isEpidemicRisk(result.disease());
        DiagnosisResult enriched = result.withRemedyAndEpidemicRisk(remedyPlan, epidemicRisk);
        // Needs the epidemic flag set above, so it is computed last. Independent of confidence on purpose.
        return enriched.withSpreadChancePercent(SpreadChanceEstimator.estimate(enriched));
    }
}
