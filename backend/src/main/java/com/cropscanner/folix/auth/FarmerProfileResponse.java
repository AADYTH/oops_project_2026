package com.cropscanner.folix.auth;

import java.time.Instant;
import java.util.UUID;

/**
 * The only account representation that ever crosses the API boundary: id, name, email and when the
 * account was created. There is deliberately no password, hash, status or session field here — the
 * entity is never serialized.
 */
public record FarmerProfileResponse(UUID id, String name, String email, Instant createdAt) {

    public static FarmerProfileResponse from(AuthenticatedFarmer farmer) {
        return new FarmerProfileResponse(farmer.id(), farmer.name(), farmer.email(), farmer.createdAt());
    }

    public static FarmerProfileResponse from(FolixFarmerEntity entity) {
        return new FarmerProfileResponse(entity.getId(), entity.getName(), entity.getEmail(), entity.getCreatedAt());
    }
}
