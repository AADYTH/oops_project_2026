package com.cropscanner.repository;

import com.cropscanner.entity.AuthorityEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AuthorityJpaRepository extends JpaRepository<AuthorityEntity, Long> {

    List<AuthorityEntity> findAllByGeocodedFalse();

    /**
     * Coarse bounding-box pre-filter so we don't pull the entire directory
     * into memory and Haversine-score every row on every request. The exact
     * distance is computed afterwards in {@code Location.distanceKmTo}.
     * 1 degree of latitude is ~111km; longitude degrees shrink with
     * cos(latitude), which the caller accounts for when computing the box.
     */
    @Query("""
            SELECT a FROM AuthorityEntity a
            WHERE a.geocoded = true
              AND a.latitude BETWEEN :minLat AND :maxLat
              AND a.longitude BETWEEN :minLon AND :maxLon
            """)
    List<AuthorityEntity> findWithinBoundingBox(
            @Param("minLat") double minLat, @Param("maxLat") double maxLat,
            @Param("minLon") double minLon, @Param("maxLon") double maxLon);
}
