package com.cropscanner.folix.agrinetwork;

/**
 * The body of {@code PUT /api/farmer/agri-network/profile}. There is NO farmer id here — whose profile
 * this is comes only from the server-side session. Every field is optional: a farmer can clear a field by
 * sending it blank/null, and none of this is required for basic account access.
 */
public record FolixAgriProfileRequest(String villageOrArea, String crops, FolixSupportCategory preferredCategory) {
}
