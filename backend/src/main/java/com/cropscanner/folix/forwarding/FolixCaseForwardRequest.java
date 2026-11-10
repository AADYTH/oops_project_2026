package com.cropscanner.folix.forwarding;

/**
 * Body of {@code POST /api/scan/reports/{id}/forward}. Only the chosen support contact — there is no
 * farmer/owner field, and any extra property a client adds is ignored.
 */
public record FolixCaseForwardRequest(Long supportContactId) {
}
