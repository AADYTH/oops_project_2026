package com.cropscanner.folix.forwarding;

import com.cropscanner.folix.agrinetwork.FolixSupportContactResponse;

import java.time.Instant;
import java.util.UUID;

/**
 * Wire shape of a report's forwarding state. For a report that has not been forwarded, {@code status} is
 * {@code NOT_FORWARDED} and {@code forwardedAt}/{@code supportContact} are null. Carries no farmer/owner
 * information.
 */
public record FolixCaseForwardResponse(UUID reportId, String status, Instant forwardedAt,
                                       FolixSupportContactResponse supportContact) {

    public static FolixCaseForwardResponse of(FolixCaseForwardEntity e) {
        return new FolixCaseForwardResponse(e.getReport().getId(), e.getStatus().name(), e.getForwardedAt(),
                FolixSupportContactResponse.from(e.getSupportContact()));
    }

    public static FolixCaseForwardResponse notForwarded(UUID reportId) {
        return new FolixCaseForwardResponse(reportId, FolixForwardStatus.NOT_FORWARDED.name(), null, null);
    }
}
