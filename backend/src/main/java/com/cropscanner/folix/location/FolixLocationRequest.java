package com.cropscanner.folix.location;

/**
 * The body of {@code PUT /api/farmer/location}: an explicit consent flag plus the coordinates the browser
 * reported after the farmer pressed the button. There is NO farmer id here — whose location this is comes only
 * from the server-side session; any extra property a client adds is ignored. The coordinates are turned into a
 * coarse region and discarded ({@link FolixRegionGrid}); they are never stored.
 */
public record FolixLocationRequest(Boolean consent, Double latitude, Double longitude) {
}
