package com.cropscanner.folix.farmeralert;

import java.util.UUID;

/**
 * Result of explicitly triggering nearby farmer alerts from an owned report.
 * Does not expose any recipient identities, counts only.
 */
public record FolixFarmerAlertTriggerResponse(
        UUID reportId,
        int alertsSent
) {}
