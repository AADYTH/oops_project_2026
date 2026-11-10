package com.cropscanner.service;

import com.cropscanner.model.AuthorityContact;
import com.cropscanner.model.Location;

import java.util.Optional;

/**
 * Resolves the nearest regional agricultural authority for a given GPS
 * coordinate. Backed by a real, importable directory (see
 * {@code AuthorityImportService} and {@code /data/kvk_directory_seed.csv}) —
 * not by hardcoded or invented contact details.
 */
public interface LocationAuthorityRepository {

    /**
     * @param location       the scan's GPS coordinates
     * @param maxRadiusKm    do not return an authority further than this away;
     *                       prevents alerting an office on the other side of the country
     *                       when the local directory has a coverage gap
     * @return the nearest authority within range, with {@code distanceKm} populated,
     *         or empty if the directory has no entry within {@code maxRadiusKm}
     */
    Optional<AuthorityContact> findNearestAuthority(Location location, double maxRadiusKm);
}
