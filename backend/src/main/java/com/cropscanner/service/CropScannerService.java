package com.cropscanner.service;

import com.cropscanner.model.AuthorityContact;
import com.cropscanner.model.DiseaseInfo;
import com.cropscanner.model.GeminiOpinion;
import com.cropscanner.model.Location;
import com.cropscanner.model.ScanAnalysisResult;
import com.cropscanner.service.SmsGatewayService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates the end-to-end scan pipeline:
 *
 * 1. classify the image and score severity via {@link InferenceService},
 * 2. decide whether the result counts as "severe",
 * 3. if severe, resolve the nearest {@link AuthorityContact} and dispatch an
 *    alert via {@link NotificationService},
 * 4. return an auditable {@link ScanAnalysisResult} whose escalation outcome
 *    explicitly records what happened (or explicitly why nothing happened).
 *
 * The 50% severity / epidemic-risk escalation rule lives here as a single,
 * explicit, unit-testable method ({@link #isSevere(DiseaseInfo)}) rather
 * than being scattered across the classifier or the controller.
 */
@Service
public class CropScannerService {

    private static final Logger log = LoggerFactory.getLogger(CropScannerService.class);
    private static final double SEVERITY_ESCALATION_THRESHOLD_PERCENT = 50.0;

    private final InferenceService inferenceService;
    private final GeminiVisionService geminiVisionService;
    private final EvidenceConfidenceService evidenceConfidenceService;
    private final LocationAuthorityRepository authorityRepository;
    private final NotificationService notificationService;
    private final SmsGatewayService smsGatewayService;
    private final ScanPersistenceService scanPersistenceService;
    private final AlertService alertService;
    private final double maxAuthoritySearchRadiusKm;
    private final long notificationTimeoutSeconds;

    public CropScannerService(
            InferenceService inferenceService,
            GeminiVisionService geminiVisionService,
            EvidenceConfidenceService evidenceConfidenceService,
            LocationAuthorityRepository authorityRepository,
            NotificationService notificationService,
            SmsGatewayService smsGatewayService,
            ScanPersistenceService scanPersistenceService,
            AlertService alertService,
            @Value("${cropscanner.escalation.max-authority-radius-km:75.0}") double maxAuthoritySearchRadiusKm,
            @Value("${cropscanner.escalation.notification-timeout-seconds:15}") long notificationTimeoutSeconds) {
        this.inferenceService = inferenceService;
        this.geminiVisionService = geminiVisionService;
        this.evidenceConfidenceService = evidenceConfidenceService;
        this.authorityRepository = authorityRepository;
        this.notificationService = notificationService;
        this.smsGatewayService = smsGatewayService;
        this.scanPersistenceService = scanPersistenceService;
        this.alertService = alertService;
        this.maxAuthoritySearchRadiusKm = maxAuthoritySearchRadiusKm;
        this.notificationTimeoutSeconds = notificationTimeoutSeconds;
    }

    /**
     * Runs the full pipeline for one uploaded image.
     *
     * @param imageBytes    raw photo bytes
     * @param plantPartHint user-supplied hint about which part of the plant was photographed
     * @param location      GPS coordinates, or {@code null} if the user declined location access
     *                      (escalation will then resolve to {@code NoAuthorityFound} rather than failing the scan)
     */
    public ScanAnalysisResult analyzeScan(byte[] imageBytes, String plantPartHint, Location location) {
        return analyzeScan(imageBytes, plantPartHint, location, false);
    }

    /**
     * @param skipQualityCheck bypasses the inference service's blur/brightness pre-check. Used when a
     *                         user re-submits after a quality rejection they believe is a false positive
     *                         (e.g. a pale but well-focused photo of powdery mildew) — see README
     *                         "Known false positives in the image-quality check".
     */
    public ScanAnalysisResult analyzeScan(byte[] imageBytes, String plantPartHint, Location location, boolean skipQualityCheck) {
        UUID scanId = UUID.randomUUID();
        Instant analyzedAt = Instant.now();

        DiseaseInfo diseaseInfo = inferenceService.classifyAndScore(imageBytes, plantPartHint, skipQualityCheck);
        diseaseInfo = withGeminiSecondOpinion(scanId, imageBytes, diseaseInfo);
        boolean severe = isSevere(diseaseInfo);

        log.info("Scan {} classified as '{}' on {} — confidence={}%, severity={}%, epidemicRisk={}, severe={}",
                scanId, diseaseInfo.diseaseName(), diseaseInfo.cropName(), diseaseInfo.confidencePercent(),
                diseaseInfo.severityPercent(), diseaseInfo.isEpidemicRisk(), severe);

        ScanAnalysisResult.EscalationOutcome outcome = severe
                ? escalate(scanId, analyzedAt, location, diseaseInfo)
                : new ScanAnalysisResult.EscalationOutcome.NotRequired();

        ScanAnalysisResult result = new ScanAnalysisResult(scanId, analyzedAt, location, diseaseInfo, severe, outcome);

        // Persist a record of every scan (not just severe ones) for the
        // outbreak heat map (Tier 3 #7) and the treatment-reminder schedule
        // (Tier 3 #10). Persistence failure must never take down a scan the
        // user is actively waiting on, so it's isolated and logged, not
        // rethrown.
        try {
            scanPersistenceService.saveScan(result);
        } catch (Exception e) {
            log.error("Failed to persist scan record {} (heat map / reminders will be missing this scan): {}",
                    scanId, e.getMessage());
        }

        return result;
    }

    /**
     * Gemini as an optional online second opinion (README "Gemini's role" /
     * "Combining local AI + Gemini"). This method NEVER throws and NEVER
     * blocks a scan on Gemini being slow/down/rate-limited/disabled —
     * {@link GeminiVisionService#getSecondOpinion} already returns a
     * graceful "unavailable" {@link GeminiOpinion} for every one of those
     * cases rather than an exception, and {@link EvidenceConfidenceService}
     * leaves the local confidence untouched whenever that's what it gets.
     * Belt-and-suspenders try/catch here anyway: a scan the local model
     * already successfully diagnosed must never be lost because of a bug in
     * the (optional, non-essential) Gemini integration.
     */
    private DiseaseInfo withGeminiSecondOpinion(UUID scanId, byte[] imageBytes, DiseaseInfo localResult) {
        if (!geminiVisionService.isConfigured()) {
            return localResult; // no geminiOpinion attached at all — nothing was requested, nothing to log
        }
        try {
            GeminiOpinion opinion = geminiVisionService.getSecondOpinion(
                    imageBytes, localResult.cropName(), localResult.diseaseName());
            EvidenceConfidenceService.CombinedResult combined = evidenceConfidenceService.combine(
                    localResult.confidenceLevel(), localResult.cropName(), localResult.diseaseName(), opinion);
            return localResult.withConfidenceAndGeminiOpinion(combined.confidenceLevel(), combined.geminiOpinion());
        } catch (Exception e) {
            log.warn("Gemini second-opinion step failed unexpectedly for scan {}; continuing with the local "
                    + "model's result unchanged: {}", scanId, e.getMessage());
            return localResult;
        }
    }

    /**
     * A scan is severe if the estimated tissue-damage percentage exceeds the
     * threshold, OR the identified pathogen is independently flagged as an
     * epidemic risk (e.g. a quarantine-notifiable disease) regardless of how
     * much of this particular plant is currently affected — because the
     * danger there is regional spread, not just damage to one plant.
     */
    private boolean isSevere(DiseaseInfo diseaseInfo) {
        return diseaseInfo.severityPercent() > SEVERITY_ESCALATION_THRESHOLD_PERCENT
                || diseaseInfo.isEpidemicRisk();
    }

    private ScanAnalysisResult.EscalationOutcome escalate(
            UUID scanId, Instant analyzedAt, Location location, DiseaseInfo diseaseInfo) {

        if (location == null) {
            log.warn("Scan {} is severe but no GPS location was supplied; cannot resolve an authority.", scanId);
            return new ScanAnalysisResult.EscalationOutcome.NoAuthorityFound(
                    "No GPS location was provided with this scan");
        }

        Optional<AuthorityContact> maybeAuthority =
                authorityRepository.findNearestAuthority(location, maxAuthoritySearchRadiusKm);

        if (maybeAuthority.isEmpty()) {
            log.warn("Scan {} is severe but no registered authority was found within {} km of ({}, {}).",
                    scanId, maxAuthoritySearchRadiusKm, location.latitude(), location.longitude());
            return new ScanAnalysisResult.EscalationOutcome.NoAuthorityFound(
                    "No registered authority within " + maxAuthoritySearchRadiusKm + " km");
        }

        AuthorityContact authority = maybeAuthority.get();

        // Provisionally build the result object the notification payload needs; the
        // escalation outcome field is filled in by the caller once we know the outcome,
        // so we construct a lightweight throwaway ScanAnalysisResult purely to carry
        // the fields the notification payload needs at send-time.
        ScanAnalysisResult resultForPayload = new ScanAnalysisResult(
                scanId, analyzedAt, location, diseaseInfo, true,
                new ScanAnalysisResult.EscalationOutcome.NotRequired());

        if (!authority.supportsWebhook()) {
            log.warn("Nearest authority '{}' for scan {} has no webhook configured; falling back to SMS.",
                    authority.name(), scanId);
            return fallbackToSms(scanId, diseaseInfo, location, authority,
                    "Authority has no automated webhook; contact them directly by phone/email");
        }

        try {
            int statusCode = notificationService.sendSevereScanAlert(resultForPayload, authority)
                    .get(notificationTimeoutSeconds, TimeUnit.SECONDS);
            ScanAnalysisResult.EscalationOutcome outcome =
                    new ScanAnalysisResult.EscalationOutcome.AuthorityNotified(authority, Instant.now(), statusCode);
            recordAlertSafely(scanId, diseaseInfo, location, authority, outcome);
            return outcome;
        } catch (Exception e) {
            log.error("Webhook notification to authority '{}' failed for scan {}: {}. Falling back to SMS.",
                    authority.name(), scanId, e.getMessage());
            return fallbackToSms(scanId, diseaseInfo, location, authority, "Webhook delivery failed: " + e.getMessage());
        }
    }

    /**
     * SMS fallback (Tier 2 upgrade #5): when the webhook channel is absent
     * or fails, try the authority's phone number directly via
     * {@link SmsGatewayService} before giving up. This is genuinely the
     * highest-leverage delivery path today, since most government offices
     * have a phone line but no webhook receiver.
     */
    private ScanAnalysisResult.EscalationOutcome fallbackToSms(
            UUID scanId, DiseaseInfo diseaseInfo, Location location, AuthorityContact authority,
            String webhookFailureReason) {

        String message = String.format(
                "CROP ALERT: %s detected on %s (severity %.0f%%%s) near %s. Scan ID %s. Reply/visit for details.",
                diseaseInfo.diseaseName(), diseaseInfo.cropName(), diseaseInfo.severityPercent(),
                diseaseInfo.isEpidemicRisk() ? ", EPIDEMIC-RISK PATHOGEN" : "",
                authority.district(), scanId);

        boolean sent = smsGatewayService.sendSms(authority.phoneNumber(), message);

        if (sent) {
            ScanAnalysisResult.EscalationOutcome outcome =
                    new ScanAnalysisResult.EscalationOutcome.SmsNotified(authority, Instant.now(), "sms");
            recordAlertSafely(scanId, diseaseInfo, location, authority, outcome);
            return outcome;
        }

        ScanAnalysisResult.EscalationOutcome outcome = new ScanAnalysisResult.EscalationOutcome.NotificationFailed(
                authority, webhookFailureReason + "; SMS fallback also failed or no phone number on file");
        recordAlertSafely(scanId, diseaseInfo, location, authority, outcome);
        return outcome;
    }

    private void recordAlertSafely(UUID scanId, DiseaseInfo diseaseInfo, Location location,
                                    AuthorityContact authority, ScanAnalysisResult.EscalationOutcome outcome) {
        try {
            alertService.recordAlert(scanId, diseaseInfo, location, authority, outcome);
        } catch (Exception e) {
            log.error("Failed to persist alert record for scan {} (authority dashboard will not show it): {}",
                    scanId, e.getMessage());
        }
    }
}
