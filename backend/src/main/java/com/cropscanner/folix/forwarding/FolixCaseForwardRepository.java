package com.cropscanner.folix.forwarding;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link FolixCaseForwardEntity}. The only farmer-facing lookup is scoped to the report's
 * owner (the farmer id comes from the server-side session, never the request), so another farmer's
 * forward is indistinguishable from none.
 *
 * The two {@code *WithReportAndContact} queries below back the authority-side forwarded-case view (Stage
 * 6E Part 2B): they {@code JOIN FETCH} the report and support contact so the mapping to a response DTO
 * can happen outside of a repository-owned transaction without hitting lazy-loading. Read-only; they
 * change nothing here or in {@code folix_scan_report}.
 */
public interface FolixCaseForwardRepository extends JpaRepository<FolixCaseForwardEntity, UUID> {

    Optional<FolixCaseForwardEntity> findByReport_IdAndReport_Farmer_Id(UUID reportId, UUID farmerId);

    /** Every forwarded case, newest first — the authority dashboard's "forwarded cases" list. */
    @Query("SELECT f FROM FolixCaseForwardEntity f JOIN FETCH f.report JOIN FETCH f.supportContact " +
            "ORDER BY f.forwardedAt DESC")
    List<FolixCaseForwardEntity> findAllWithReportAndContactOrderByForwardedAtDesc();

    /** One forwarded case by report id — backs "view forwarded report details". Absent if never forwarded. */
    @Query("SELECT f FROM FolixCaseForwardEntity f JOIN FETCH f.report JOIN FETCH f.supportContact " +
            "WHERE f.report.id = :reportId")
    Optional<FolixCaseForwardEntity> findByReport_IdWithReportAndContact(@Param("reportId") UUID reportId);

    @org.springframework.transaction.annotation.Transactional
    void deleteByReport_Id(UUID reportId);
}
