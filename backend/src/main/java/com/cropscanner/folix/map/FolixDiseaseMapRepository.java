package com.cropscanner.folix.map;

import com.cropscanner.folix.FolixScanReportEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read-only access for the Disease Map. Deliberately a bare {@link Repository} (no inherited findAll / count /
 * delete) and deliberately NOT part of {@code FolixScanReportRepository}, whose every query is farmer-scoped:
 * this is the one place that reads across farmers, and it only ever returns the narrow {@link FolixMapRow}
 * projection of reports that carry a coarse region — never an entity, never a name or email, never exact
 * coordinates (none exist).
 */
public interface FolixDiseaseMapRepository extends Repository<FolixScanReportEntity, UUID> {

    @Query("select new com.cropscanner.folix.map.FolixMapRow("
            + "r.regionLatMdeg, r.regionLngMdeg, r.crop, r.disease, r.severity, r.confidence, r.farmer.id, r.severityPercent) "
            + "from FolixScanReportEntity r "
            + "where r.createdAt >= :since and r.regionLatMdeg is not null and r.regionLngMdeg is not null")
    List<FolixMapRow> findLocatedRowsSince(@Param("since") Instant since);
}
