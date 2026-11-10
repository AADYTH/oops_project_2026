package com.cropscanner.folix.agrinetwork;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Persistence for {@link FolixFarmerAgriProfileEntity}. The id IS the farmer id, so {@code findById} is
 * inherently scoped to one farmer; callers pass {@code AuthenticatedFarmer#id()} only — never an id
 * supplied by the request.
 */
public interface FolixFarmerAgriProfileRepository extends JpaRepository<FolixFarmerAgriProfileEntity, UUID> {
}
