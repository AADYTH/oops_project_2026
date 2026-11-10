package com.cropscanner.folix.agrinetwork;

import java.time.Instant;

/**
 * The caller's own agricultural profile. When nothing has been saved yet, every field except
 * {@code hasProfile} is null — this is a normal, expected state (see class doc on
 * {@link FolixFarmerAgriProfileEntity}), never an error.
 */
public record FolixAgriProfileResponse(boolean hasProfile, String villageOrArea, String crops,
                                        FolixSupportCategory preferredCategory, Instant updatedAt) {

    public static FolixAgriProfileResponse empty() {
        return new FolixAgriProfileResponse(false, null, null, null, null);
    }

    public static FolixAgriProfileResponse of(FolixFarmerAgriProfileEntity e) {
        return new FolixAgriProfileResponse(true, e.getVillageOrArea(), e.getCrops(),
                e.getPreferredCategory(), e.getUpdatedAt());
    }
}
