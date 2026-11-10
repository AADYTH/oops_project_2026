package com.cropscanner.folix;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link FolixScanReportEntity}.
 *
 * Stage 6B: every farmer-facing read is scoped to the owning farmer. The Stage 5 unscoped queries
 * ({@code findAllByOrderByCreatedAtDesc}, {@code countByNeedsHumanReviewTrue}) were removed on purpose so
 * that no service can list or count "everyone's" reports by accident. Note that the inherited
 * {@code findById}/{@code findAll}/{@code count} from {@link JpaRepository} are NOT owner-scoped —
 * {@link FolixReportService} must never call them (an architecture test enforces that).
 *
 * All owner parameters below are farmer ids taken from the server-side session
 * ({@code AuthenticatedFarmer#id()}), never from the request.
 */
public interface FolixScanReportRepository extends JpaRepository<FolixScanReportEntity, UUID> {

    /** One farmer's reports, newest first, paginated. */
    Page<FolixScanReportEntity> findAllByFarmer_IdOrderByCreatedAtDesc(UUID farmerId, Pageable pageable);

    /**
     * A report by id ONLY if it belongs to the given farmer. Someone else's report and a non-existent
     * report are indistinguishable to the caller (both empty).
     */
    Optional<FolixScanReportEntity> findByIdAndFarmer_Id(UUID id, UUID farmerId);

    long countByFarmer_Id(UUID farmerId);

    long countByFarmer_IdAndNeedsHumanReviewTrue(UUID farmerId);

    Optional<FolixScanReportEntity> findFirstByFarmer_IdOrderByCreatedAtDesc(UUID farmerId);

    java.util.List<FolixScanReportEntity> findAllByFarmer_Id(UUID farmerId);

    long countByStatus(FolixReportStatus status);
}
