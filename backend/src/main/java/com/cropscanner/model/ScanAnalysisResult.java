package com.cropscanner.model;

import java.time.Instant;
import java.util.UUID;

/**
 * The complete, persisted result of one scan: what was found, how severe it
 * is, and — if applicable — what happened as a result of escalation.
 */
public record ScanAnalysisResult(
        UUID scanId,
        Instant analyzedAt,
        Location location,
        DiseaseInfo diseaseInfo,
        boolean severe,
        EscalationOutcome escalationOutcome
) {

    /**
     * Sealed result of the escalation pipeline so callers must handle every
     * outcome explicitly (exhaustive switch) instead of null-checking a
     * generic "status" string.
     */
    public sealed interface EscalationOutcome
            permits EscalationOutcome.NotRequired,
                    EscalationOutcome.AuthorityNotified,
                    EscalationOutcome.SmsNotified,
                    EscalationOutcome.NoAuthorityFound,
                    EscalationOutcome.NotificationFailed {

        record NotRequired() implements EscalationOutcome {}

        record AuthorityNotified(AuthorityContact authority, Instant notifiedAt, int httpStatusCode)
                implements EscalationOutcome {}

        /**
         * SMS fallback (Tier 2 upgrade #5): used when the nearest authority has
         * no webhook configured, or the webhook attempt failed, but a phone
         * number is on file. Distinct from {@link AuthorityNotified} so the
         * frontend/dashboard can show "notified by SMS" rather than implying a
         * machine-acknowledged webhook delivery.
         */
        record SmsNotified(AuthorityContact authority, Instant notifiedAt, String provider)
                implements EscalationOutcome {}

        record NoAuthorityFound(String reason) implements EscalationOutcome {}

        record NotificationFailed(AuthorityContact authority, String reason) implements EscalationOutcome {}
    }
}
