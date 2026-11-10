package com.cropscanner.folix.farmeralert;

public record FolixAuthorityEscalationRequest(
        String note,
        Double latitude,
        Double longitude
) {
    public FolixAuthorityEscalationRequest(String note) {
        this(note, null, null);
    }
}
