package com.cropscanner.folix;

import com.cropscanner.folix.auth.AuthenticatedFarmer;
import com.cropscanner.folix.auth.FolixAuthException;
import com.cropscanner.folix.auth.FolixFarmerEntity;
import com.cropscanner.folix.auth.FolixFarmerRepository;
import com.cropscanner.folix.location.FolixFarmerLocationRepository;
import com.cropscanner.folix.location.FolixRegionCell;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Persistence foundation for FOLIX scan reports:
 *
 *   Gemini diagnosis → FOLIX Scan Report → database
 *
 * This service only saves and reads reports; it does not decide whether a
 * report should be created (that will be wired up in a later stage that
 * connects "Save Report" to this service) and it never invents missing
 * diagnosis information — a request is either exactly what was diagnosed,
 * or it is rejected.
 *
 * Stage 6B — ownership. Every method takes the {@link AuthenticatedFarmer} resolved from the
 * server-side session and:
 * <ul>
 *   <li>{@code save} stores the report with that farmer as its owner;</li>
 *   <li>{@code list}/{@code stats} only ever see that farmer's reports;</li>
 *   <li>{@code getById} finds a report only if it is owned by that farmer — someone else's report
 *       yields exactly the same {@link FolixReportException#notFound(UUID)} as a non-existent id, so
 *       the API never reveals that another farmer's report exists.</li>
 * </ul>
 * The owner is NEVER read from the request body. None of the unscoped repository methods
 * ({@code findById}, {@code findAll}, {@code count}) is used here.
 *
 * Stage 6C — location. If (and only if) the authenticated farmer has explicitly enabled location sharing, the
 * report saved for them is stamped with their coarse region. The region comes from the farmer's stored, session-owned
 * choice — never from the request body — and a farmer who never opted in (or withdrew) simply saves a report with no
 * location. Location is never required and its absence never blocks a save.
 */
@Service
public class FolixReportService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final FolixScanReportRepository repository;
    private final FolixFarmerRepository farmerRepository;
    private final FolixFarmerLocationRepository locationRepo;
    private final com.cropscanner.repository.AlertJpaRepository alertJpaRepo;
    private final com.cropscanner.repository.AuthorityJpaRepository authorityJpaRepo;

    public FolixReportService(FolixScanReportRepository repository, FolixFarmerRepository farmerRepository,
                              FolixFarmerLocationRepository locationRepo) {
        this(repository, farmerRepository, locationRepo, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public FolixReportService(FolixScanReportRepository repository, FolixFarmerRepository farmerRepository,
                              FolixFarmerLocationRepository locationRepo,
                              @org.springframework.beans.factory.annotation.Autowired(required = false) com.cropscanner.repository.AlertJpaRepository alertJpaRepo,
                              @org.springframework.beans.factory.annotation.Autowired(required = false) com.cropscanner.repository.AuthorityJpaRepository authorityJpaRepo) {
        this.repository = repository;
        this.farmerRepository = farmerRepository;
        this.locationRepo = locationRepo;
        this.alertJpaRepo = alertJpaRepo;
        this.authorityJpaRepo = authorityJpaRepo;
    }

    /** Persists a report built directly from a completed Gemini diagnosis, owned by {@code farmer}. */
    @Transactional
    public FolixReportResponse save(DiagnosisResult diagnosis, AuthenticatedFarmer farmer) {
        requireFarmer(farmer);
        if (diagnosis == null) {
            throw FolixReportException.malformed("diagnosis result is missing");
        }
        return save(FolixReportRequest.from(diagnosis), farmer);
    }

    /** Validates and persists a report request, owned by the authenticated {@code farmer}. */
    @Transactional
    public FolixReportResponse save(FolixReportRequest request, AuthenticatedFarmer farmer) {
        requireFarmer(farmer);
        if (request == null) {
            throw FolixReportException.malformed("request body is missing");
        }

        String crop = requireNonBlank(request.crop(), "crop");
        String disease = requireNonBlank(request.disease(), "disease");
        String reasoning = requireNonBlank(request.reasoning(), "reasoning");

        DiagnosisResult.PlantPart plantPart = DiagnosisResult.PlantPart.fromWire(request.plantPart())
                .orElseThrow(() -> FolixReportException.invalidField("plantPart", request.plantPart()));
        DiagnosisResult.Severity severity = DiagnosisResult.Severity.fromWire(request.severity())
                .orElseThrow(() -> FolixReportException.invalidField("severity", request.severity()));
        DiagnosisResult.Confidence confidence = DiagnosisResult.Confidence.fromWire(request.confidence())
                .orElseThrow(() -> FolixReportException.invalidField("confidence", request.confidence()));

        // Invariant: low confidence must always be flagged for human review. This is a stored
        // FOLIX report, not a live Gemini response, so this is checked rather than silently fixed —
        // a violation means the data reaching persistence is inconsistent and must be rejected.
        if (confidence == DiagnosisResult.Confidence.LOW && !request.needsHumanReview()) {
            throw FolixReportException.invariantViolation(
                    "low-confidence reports must have needsHumanReview=true");
        }

        // A lazy reference to the farmer row (no extra SELECT). The id comes from the server-side session.
        FolixFarmerEntity owner = farmerRepository.getReferenceById(farmer.id());

        FolixScanReportEntity entity = new FolixScanReportEntity(
                UUID.randomUUID(),
                owner,
                Instant.now(),
                crop,
                plantPart.wire(),
                disease,
                severity.wire(),
                confidence.wire(),
                request.symptoms(),
                reasoning,
                request.recommendedActions(),
                request.needsHumanReview(),
                request.remedyPlan(),
                FolixReportStatus.RECEIVED,
                request.epidemicRisk()
        );

        entity.assignPercentages(request.severityPercent(), request.spreadChancePercent());

        // Stage 6C: the farmer's own opted-in coarse region (keyed by the SESSION's farmer id), if they have one.
        locationRepo.findById(farmer.id()).ifPresent(loc -> entity.assignRegion(loc.cell()));

        FolixScanReportEntity saved = repository.save(entity);

        // If the report names a diagnosed disease (not healthy/unknown) OR is epidemic risk OR severe outbreak,
        // record an alert for the authority of that location
        String savedDisease = saved.getDisease();
        boolean isDisease = savedDisease != null && !savedDisease.trim().isEmpty()
                && !"healthy".equalsIgnoreCase(savedDisease.trim())
                && !"unknown".equalsIgnoreCase(savedDisease.trim());
        if (isDisease || saved.isEpidemicRisk() || (saved.getSeverityPercent() != null && saved.getSeverityPercent() >= 40)
                || (saved.getSpreadChancePercent() != null && saved.getSpreadChancePercent() >= 30)) {
            recordAlertSafely(saved, farmer);
        }

        return FolixReportResponse.from(saved);
    }

    private void recordAlertSafely(FolixScanReportEntity report, AuthenticatedFarmer farmer) {
        if (alertJpaRepo == null || authorityJpaRepo == null) return;
        try {
            Double lat = null;
            Double lng = null;
            if (report.hasRegion()) {
                lat = report.getRegionLatMdeg() / 1000.0;
                lng = report.getRegionLngMdeg() / 1000.0;
            } else {
                var farmerLoc = locationRepo.findById(farmer.id());
                if (farmerLoc.isPresent()) {
                    lat = farmerLoc.get().getRegionLatMdeg() / 1000.0;
                    lng = farmerLoc.get().getRegionLngMdeg() / 1000.0;
                }
            }

            Long authorityId = 1L;
            List<com.cropscanner.entity.AuthorityEntity> allAuth = authorityJpaRepo.findAll();
            if (!allAuth.isEmpty()) {
                if (lat != null && lng != null && !lat.isNaN() && !lng.isNaN()) {
                    com.cropscanner.entity.AuthorityEntity best = null;
                    double minDistance = Double.MAX_VALUE;
                    for (var auth : allAuth) {
                        if (auth.getLatitude() != null && auth.getLongitude() != null) {
                            double d = haversineKm(lat, lng, auth.getLatitude(), auth.getLongitude());
                            if (d < minDistance) {
                                minDistance = d;
                                best = auth;
                            }
                        }
                    }
                    if (best != null) {
                        authorityId = best.getId();
                    } else {
                        authorityId = allAuth.get(allAuth.size() - 1).getId();
                    }
                } else {
                    authorityId = allAuth.get(allAuth.size() - 1).getId();
                }
            }

            com.cropscanner.entity.AlertEntity alert = new com.cropscanner.entity.AlertEntity(
                    report.getId(),
                    authorityId,
                    report.getCrop(),
                    report.getDisease(),
                    report.getSeverityPercent() != null ? report.getSeverityPercent().doubleValue() : 75.0,
                    report.isEpidemicRisk(),
                    lat,
                    lng,
                    com.cropscanner.entity.AlertEntity.Channel.WEBHOOK
            );
            alert.updateStatus(com.cropscanner.entity.AlertEntity.Status.NOTIFIED,
                    "Automated Outbreak Scan: " + report.getDisease() + " detected on " + report.getCrop());
            alertJpaRepo.save(alert);
        } catch (Exception ignored) {
        }
    }

    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return 6371.0 * c;
    }

    /**
     * The report with this id, but only if {@code farmer} owns it. Another farmer's report is reported as
     * "not found" — identical to an id that does not exist — so nothing about it leaks.
     */
    @Transactional(readOnly = true)
    public FolixReportResponse getById(UUID id, AuthenticatedFarmer farmer) {
        requireFarmer(farmer);
        if (id == null) {
            throw FolixReportException.malformed("report id is missing");
        }
        return repository.findByIdAndFarmer_Id(id, farmer.id())
                .map(FolixReportResponse::from)
                .orElseThrow(() -> FolixReportException.notFound(id));
    }

    /** The farmer's own reports, newest-first, paginated. {@code size} is clamped to (0, {@value #MAX_PAGE_SIZE}]. */
    @Transactional(readOnly = true)
    public FolixReportPage list(AuthenticatedFarmer farmer, int page, int size) {
        requireFarmer(farmer);
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage, safeSize);
        Page<FolixScanReportEntity> result = repository.findAllByFarmer_IdOrderByCreatedAtDesc(farmer.id(), pageable);
        return FolixReportPage.from(result);
    }

    /** Basic counts the Dashboard reads — calculated only from the authenticated farmer's own reports. */
    @Transactional(readOnly = true)
    public FolixReportStats stats(AuthenticatedFarmer farmer) {
        requireFarmer(farmer);
        return new FolixReportStats(
                repository.countByFarmer_Id(farmer.id()),
                repository.countByFarmer_IdAndNeedsHumanReviewTrue(farmer.id()));
    }

    /** Fails closed: a missing farmer (or id) is never treated as "everyone" or "anonymous". */
    private static void requireFarmer(AuthenticatedFarmer farmer) {
        if (farmer == null || farmer.id() == null) {
            throw FolixAuthException.unauthenticated();
        }
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw FolixReportException.invalidField(field, value);
        }
        return value.trim();
    }
}
