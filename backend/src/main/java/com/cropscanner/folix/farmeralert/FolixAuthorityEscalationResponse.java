package com.cropscanner.folix.farmeralert;

import java.util.UUID;

public record FolixAuthorityEscalationResponse(
        UUID reportId,
        Long authorityId,
        String authorityName,
        String message
) {
}
