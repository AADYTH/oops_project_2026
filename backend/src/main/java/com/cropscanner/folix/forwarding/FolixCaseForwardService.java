package com.cropscanner.folix.forwarding;

import com.cropscanner.folix.FolixReportException;
import com.cropscanner.folix.FolixScanReportEntity;
import com.cropscanner.folix.FolixScanReportRepository;
import com.cropscanner.folix.agrinetwork.FolixSupportContactEntity;
import com.cropscanner.folix.agrinetwork.FolixSupportContactRepository;
import com.cropscanner.folix.auth.AuthenticatedFarmer;
import com.cropscanner.folix.auth.FolixAuthException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Stage 6E Part 1 — manual case forwarding: the owning farmer explicitly forwards one of their own saved
 * reports to one active Agricultural Network support contact.
 *
 * <ul>
 *   <li>The farmer is always the {@link AuthenticatedFarmer} resolved from the server-side session; no
 *       farmer id is ever read from the request.</li>
 *   <li>The report is loaded with the owner-scoped {@code findByIdAndFarmer_Id}; someone else's report is
 *       answered exactly like a non-existent one (404).</li>
 *   <li>Only an explicit call to {@link #forward} creates a forward. Nothing forwards automatically, and
 *       nothing here sends, notifies or contacts anyone — it only records the forward.</li>
 *   <li>A report can be forwarded once; a second attempt is a 409 and changes nothing.</li>
 * </ul>
 * The unscoped {@code findById} of the report repository is never used.
 */
@Service
public class FolixCaseForwardService {

    private final FolixScanReportRepository reportRepo;
    private final FolixSupportContactRepository contactRepo;
    private final FolixCaseForwardRepository forwardRepo;
    private final Clock clock;

    @Autowired
    public FolixCaseForwardService(FolixScanReportRepository reportRepo, FolixSupportContactRepository contactRepo,
                                    FolixCaseForwardRepository forwardRepo) {
        this(reportRepo, contactRepo, forwardRepo, Clock.systemUTC());
    }

    public FolixCaseForwardService(FolixScanReportRepository reportRepo, FolixSupportContactRepository contactRepo,
                                    FolixCaseForwardRepository forwardRepo, Clock clock) {
        this.reportRepo = reportRepo;
        this.contactRepo = contactRepo;
        this.forwardRepo = forwardRepo;
        this.clock = clock;
    }

    @Transactional
    public FolixCaseForwardResponse forward(UUID reportId, FolixCaseForwardRequest request, AuthenticatedFarmer farmer) {
        requireFarmer(farmer);
        FolixScanReportEntity report = ownedReport(reportId, farmer);

        if (request == null || request.supportContactId() == null) {
            throw FolixCaseForwardException.contactRequired();
        }
        if (forwardRepo.findByReport_IdAndReport_Farmer_Id(report.getId(), farmer.id()).isPresent()) {
            throw FolixCaseForwardException.alreadyForwarded();
        }
        FolixSupportContactEntity contact = contactRepo.findById(request.supportContactId())
                .filter(FolixSupportContactEntity::isActive)
                .orElseThrow(FolixCaseForwardException::contactUnavailable);

        FolixCaseForwardEntity entity = new FolixCaseForwardEntity(UUID.randomUUID(), report, contact,
                FolixForwardStatus.FORWARDED, clock.instant());
        try {
            return FolixCaseForwardResponse.of(forwardRepo.saveAndFlush(entity));
        } catch (DataIntegrityViolationException e) {
            // Two simultaneous forwards of the same report: the unique constraint on report_id let only one through.
            throw FolixCaseForwardException.alreadyForwarded();
        }
    }

    /** The forwarding state of one of the farmer's own reports ({@code NOT_FORWARDED} if never forwarded). */
    @Transactional(readOnly = true)
    public FolixCaseForwardResponse status(UUID reportId, AuthenticatedFarmer farmer) {
        requireFarmer(farmer);
        FolixScanReportEntity report = ownedReport(reportId, farmer);
        return forwardRepo.findByReport_IdAndReport_Farmer_Id(report.getId(), farmer.id())
                .map(FolixCaseForwardResponse::of)
                .orElseGet(() -> FolixCaseForwardResponse.notForwarded(report.getId()));
    }

    private FolixScanReportEntity ownedReport(UUID reportId, AuthenticatedFarmer farmer) {
        if (reportId == null) {
            throw FolixReportException.malformed("report id is missing");
        }
        return reportRepo.findByIdAndFarmer_Id(reportId, farmer.id())
                .orElseThrow(() -> FolixReportException.notFound(reportId));
    }

    /** Fails closed: a missing farmer (or id) is never treated as "anonymous". */
    private static void requireFarmer(AuthenticatedFarmer farmer) {
        if (farmer == null || farmer.id() == null) {
            throw FolixAuthException.unauthenticated();
        }
    }
}
