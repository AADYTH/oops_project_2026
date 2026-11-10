package com.cropscanner.service;

import com.cropscanner.entity.ReminderEntity;
import com.cropscanner.entity.ScanRecordEntity;
import com.cropscanner.model.DiseaseInfo;
import com.cropscanner.model.Location;
import com.cropscanner.model.ScanAnalysisResult;
import com.cropscanner.repository.ReminderJpaRepository;
import com.cropscanner.repository.ScanRecordJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Persists every scan (Tier 3 upgrade #7's data source) and derives the
 * treatment-tracking reminder schedule from the remedy plan already
 * attached to the diagnosis (Tier 3 upgrade #10) — the dosage/frequency/PHI
 * data already exists in {@code remedies.json}, this just turns it into
 * dated reminders instead of static text the user has to remember.
 */
@Service
public class ScanPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(ScanPersistenceService.class);

    /** Matches the first integer in strings like "Every 7 days" or "10 days". Best-effort, not a full parser. */
    private static final Pattern FIRST_NUMBER = Pattern.compile("(\\d+)");

    private final ScanRecordJpaRepository scanRecordRepository;
    private final ReminderJpaRepository reminderRepository;

    public ScanPersistenceService(ScanRecordJpaRepository scanRecordRepository, ReminderJpaRepository reminderRepository) {
        this.scanRecordRepository = scanRecordRepository;
        this.reminderRepository = reminderRepository;
    }

    public void saveScan(ScanAnalysisResult result) {
        DiseaseInfo d = result.diseaseInfo();
        Location loc = result.location();

        ScanRecordEntity entity = new ScanRecordEntity(
                result.scanId(),
                result.analyzedAt(),
                d.cropName(),
                d.diseaseName(),
                d.affectedPlantPart().name(),
                d.confidencePercent(),
                d.severityPercent(),
                d.confidenceLevel().name(),
                d.isEpidemicRisk(),
                result.severe(),
                describeOutcome(result),
                loc == null ? null : roundForAnonymity(loc.latitude()),
                loc == null ? null : roundForAnonymity(loc.longitude())
        );
        scanRecordRepository.save(entity);

        generateReminders(result.scanId(), d);
    }

    /** Rounds to 3 decimal places (~110m) so aggregated heat-map queries never expose an exact farm location. */
    private double roundForAnonymity(double coordinate) {
        return Math.round(coordinate * 1000.0) / 1000.0;
    }

    private String describeOutcome(ScanAnalysisResult result) {
        return switch (result.escalationOutcome()) {
            case ScanAnalysisResult.EscalationOutcome.NotRequired ignored -> "NOT_REQUIRED";
            case ScanAnalysisResult.EscalationOutcome.AuthorityNotified ignored -> "AUTHORITY_NOTIFIED";
            case ScanAnalysisResult.EscalationOutcome.SmsNotified ignored -> "AUTHORITY_NOTIFIED_BY_SMS";
            case ScanAnalysisResult.EscalationOutcome.NoAuthorityFound ignored -> "NO_AUTHORITY_FOUND";
            case ScanAnalysisResult.EscalationOutcome.NotificationFailed ignored -> "NOTIFICATION_FAILED";
        };
    }

    private void generateReminders(java.util.UUID scanId, DiseaseInfo d) {
        List<ReminderEntity> reminders = new ArrayList<>();
        Instant now = Instant.now();

        for (DiseaseInfo.OrganicRemedy remedy : d.remedyPlan().organicRemedies()) {
            parseIntervalDays(remedy.applicationFrequency()).ifPresent(days ->
                    reminders.add(new ReminderEntity(scanId, d.cropName(), d.diseaseName(), remedy.name(),
                            ReminderEntity.ReminderType.REAPPLICATION, now.plus(days, ChronoUnit.DAYS))));
        }

        for (DiseaseInfo.ChemicalRemedy remedy : d.remedyPlan().chemicalRemedies()) {
            parseIntervalDays(remedy.preHarvestIntervalDays()).ifPresent(days ->
                    reminders.add(new ReminderEntity(scanId, d.cropName(), d.diseaseName(),
                            remedy.name() + " — pre-harvest interval",
                            ReminderEntity.ReminderType.PRE_HARVEST_INTERVAL_END, now.plus(days, ChronoUnit.DAYS))));
        }

        if (!reminders.isEmpty()) {
            reminderRepository.saveAll(reminders);
            log.debug("Generated {} treatment reminder(s) for scan {}", reminders.size(), scanId);
        }
    }

    private java.util.Optional<Integer> parseIntervalDays(String text) {
        if (text == null) return java.util.Optional.empty();
        Matcher matcher = FIRST_NUMBER.matcher(text);
        if (matcher.find()) {
            try {
                return java.util.Optional.of(Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignored) {
                return java.util.Optional.empty();
            }
        }
        return java.util.Optional.empty();
    }
}
