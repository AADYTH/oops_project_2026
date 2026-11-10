package com.cropscanner.service.impl;

import com.cropscanner.entity.AuthorityEntity;
import com.cropscanner.model.AuthorityContact;
import com.cropscanner.model.Location;
import com.cropscanner.repository.AuthorityJpaRepository;
import com.cropscanner.service.LocationAuthorityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Repository
public class JpaLocationAuthorityRepository implements LocationAuthorityRepository {

    private static final Logger log = LoggerFactory.getLogger(JpaLocationAuthorityRepository.class);
    private static final double KM_PER_DEGREE_LAT = 111.32;

    private final AuthorityJpaRepository jpaRepository;

    public JpaLocationAuthorityRepository(AuthorityJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<AuthorityContact> findNearestAuthority(Location location, double maxRadiusKm) {
        double latDeltaDeg = maxRadiusKm / KM_PER_DEGREE_LAT;
        double lonDeltaDeg = maxRadiusKm / (KM_PER_DEGREE_LAT * Math.cos(Math.toRadians(location.latitude())) + 1e-6);

        List<AuthorityEntity> candidates = jpaRepository.findWithinBoundingBox(
                location.latitude() - latDeltaDeg, location.latitude() + latDeltaDeg,
                location.longitude() - lonDeltaDeg, location.longitude() + lonDeltaDeg);

        if (candidates.isEmpty()) {
            log.warn("No geocoded authority found within {} km bounding box of ({}, {}). " +
                            "The imported directory likely has a coverage gap for this region.",
                    maxRadiusKm, location.latitude(), location.longitude());
            return Optional.empty();
        }

        return candidates.stream()
                .map(entity -> toContactWithDistance(entity, location))
                .filter(contact -> contact.distanceKm() <= maxRadiusKm)
                .min(Comparator.comparingDouble(AuthorityContact::distanceKm));
    }

    private AuthorityContact toContactWithDistance(AuthorityEntity entity, Location scanLocation) {
        Location authorityLocation = new Location(entity.getLatitude(), entity.getLongitude());
        double distanceKm = scanLocation.distanceKmTo(authorityLocation);
        return new AuthorityContact(
                entity.getId(),
                entity.getName(),
                entity.getDistrict(),
                entity.getState(),
                authorityLocation,
                entity.getWebhookUrl(),
                entity.getPhoneNumber(),
                entity.getEmail(),
                distanceKm
        );
    }
}
