package com.cropscanner.folix.location;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Persistence for {@link FolixFarmerLocationEntity}. The id IS the farmer id, so {@code findById} /
 * {@code deleteById} are inherently scoped to one farmer; callers pass {@code AuthenticatedFarmer#id()} only.
 */
public interface FolixFarmerLocationRepository extends JpaRepository<FolixFarmerLocationEntity, UUID> {

    /**
     * Withdrawal of consent: removes the region from every report the given farmer saved, so they stop
     * contributing to the Disease Map. The reports themselves are NOT deleted or otherwise changed.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update FolixScanReportEntity r set r.regionLatMdeg = null, r.regionLngMdeg = null "
            + "where r.farmer.id = :farmerId")
    int clearRegionFromReportsOf(@Param("farmerId") UUID farmerId);

    /**
     * Finds nearby farmers whose coarse region cell falls within the bounding box of the 3x3 neighbourhood
     * approximation around a report's cell, excluding the sender farmer.
     */
    @Query("select l from FolixFarmerLocationEntity l "
            + "where l.regionLatMdeg between :minLat and :maxLat "
            + "and l.regionLngMdeg between :minLng and :maxLng "
            + "and l.farmerId <> :excludeFarmerId")
    List<FolixFarmerLocationEntity> findNearbyExcluding(
            @Param("minLat") int minLat, @Param("maxLat") int maxLat,
            @Param("minLng") int minLng, @Param("maxLng") int maxLng,
            @Param("excludeFarmerId") UUID excludeFarmerId);

    /**
     * Finds nearby farmers whose coarse region cell falls within the bounding box (e.g. for authority broadcast).
     */
    @Query("select l from FolixFarmerLocationEntity l "
            + "where l.regionLatMdeg between :minLat and :maxLat "
            + "and l.regionLngMdeg between :minLng and :maxLng")
    List<FolixFarmerLocationEntity> findNearby(
            @Param("minLat") int minLat, @Param("maxLat") int maxLat,
            @Param("minLng") int minLng, @Param("maxLng") int maxLng);
}
