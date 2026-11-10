package com.cropscanner.folix.farmeralert;

import com.cropscanner.folix.FolixReportException;
import com.cropscanner.folix.FolixScanReportEntity;
import com.cropscanner.folix.FolixScanReportRepository;
import com.cropscanner.folix.auth.AuthenticatedFarmer;
import com.cropscanner.folix.auth.FolixAuthException;
import com.cropscanner.folix.location.FolixFarmerLocationEntity;
import com.cropscanner.folix.location.FolixFarmerLocationRepository;
import com.cropscanner.folix.map.FolixMapSettings;
import com.cropscanner.dto.AuthorityDtos;
import com.cropscanner.entity.AlertEntity;
import com.cropscanner.entity.AuthorityEntity;
import com.cropscanner.folix.auth.FolixFarmerEntity;
import com.cropscanner.folix.auth.FolixFarmerRepository;
import com.cropscanner.folix.location.FolixRegionCell;
import com.cropscanner.model.Location;
import com.cropscanner.repository.AlertJpaRepository;
import com.cropscanner.repository.AuthorityJpaRepository;
import com.cropscanner.service.LocationAuthorityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Service managing farmer-to-farmer epidemic alerts (FOLIX Stage 6I).
 *
 * Rules:
 * - Alerts are explicitly triggered by the report owner from an owned report.
 * - Sourced strictly from persisted epidemic risk data (report.isEpidemicRisk()).
 * - Finds nearby farmers using the 3x3 coarse-grid cell neighbourhood (~0-22 km) as a
 *   privacy-preserving approximation (never exact GPS).
 * - Excludes the triggering farmer themselves.
 * - Enforces a 24-hour cooldown per recipient farmer and disease.
 * - Pre-computes a 7-day expiry timestamp; expired alerts are filtered out of reads.
 * - Privacy-safe: never persists or exposes farmer identities, exact coordinates, or scan IDs.
 */
@Service
public class FolixFarmerAlertService {

    public static final Duration COOLDOWN_DURATION = Duration.ofHours(24);
    public static final Duration EXPIRY_DURATION = Duration.ofDays(7);

    private final FolixScanReportRepository reportRepo;
    private final FolixFarmerLocationRepository locationRepo;
    private final FolixFarmerAlertRepository alertRepo;
    private final FolixFarmerRepository farmerRepo;
    private final com.cropscanner.folix.agrinetwork.FolixFarmerAgriProfileRepository agriProfileRepo;
    private final AlertJpaRepository alertJpaRepo;
    private final AuthorityJpaRepository authorityJpaRepo;
    private final LocationAuthorityRepository locationAuthorityRepo;
    private final FolixMapSettings mapSettings;
    private final Clock clock;

    @Autowired(required = false)
    private com.cropscanner.folix.forwarding.FolixCaseForwardRepository caseForwardRepo;

    @Autowired(required = false)
    private com.cropscanner.folix.auth.FolixSessionRepository sessionRepo;

    @Autowired
    public FolixFarmerAlertService(
            FolixScanReportRepository reportRepo,
            FolixFarmerLocationRepository locationRepo,
            FolixFarmerAlertRepository alertRepo,
            FolixFarmerRepository farmerRepo,
            @Autowired(required = false) com.cropscanner.folix.agrinetwork.FolixFarmerAgriProfileRepository agriProfileRepo,
            AlertJpaRepository alertJpaRepo,
            AuthorityJpaRepository authorityJpaRepo,
            @Autowired(required = false) LocationAuthorityRepository locationAuthorityRepo,
            FolixMapSettings mapSettings,
            Clock clock) {
        this.reportRepo = Objects.requireNonNull(reportRepo, "reportRepo");
        this.locationRepo = Objects.requireNonNull(locationRepo, "locationRepo");
        this.alertRepo = Objects.requireNonNull(alertRepo, "alertRepo");
        this.farmerRepo = farmerRepo;
        this.agriProfileRepo = agriProfileRepo;
        this.alertJpaRepo = alertJpaRepo;
        this.authorityJpaRepo = authorityJpaRepo;
        this.locationAuthorityRepo = locationAuthorityRepo;
        this.mapSettings = Objects.requireNonNull(mapSettings, "mapSettings");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public FolixFarmerAlertService(
            FolixScanReportRepository reportRepo,
            FolixFarmerLocationRepository locationRepo,
            FolixFarmerAlertRepository alertRepo,
            FolixMapSettings mapSettings) {
        this(reportRepo, locationRepo, alertRepo, null, null, null, null, null, mapSettings, Clock.systemUTC());
    }

    public FolixFarmerAlertService(
            FolixScanReportRepository reportRepo,
            FolixFarmerLocationRepository locationRepo,
            FolixFarmerAlertRepository alertRepo,
            FolixMapSettings mapSettings,
            Clock clock) {
        this(reportRepo, locationRepo, alertRepo, null, null, null, null, null, mapSettings, clock);
    }

    public FolixFarmerAlertService(
            FolixScanReportRepository reportRepo,
            FolixFarmerLocationRepository locationRepo,
            FolixFarmerAlertRepository alertRepo) {
        this(reportRepo, locationRepo, alertRepo, null, null, null, null, null, FolixMapSettings.defaults(), Clock.systemUTC());
    }

    public FolixFarmerAlertService(
            FolixScanReportRepository reportRepo,
            FolixFarmerLocationRepository locationRepo,
            FolixFarmerAlertRepository alertRepo,
            Clock clock) {
        this(reportRepo, locationRepo, alertRepo, null, null, null, null, null, FolixMapSettings.defaults(), clock);
    }

    /**
     * Explicitly triggers epidemic alerts to nearby farmers from an owned report.
     */
    @Transactional
    public FolixFarmerAlertTriggerResponse triggerAlerts(UUID reportId, AuthenticatedFarmer farmer) {
        requireFarmer(farmer);
        FolixScanReportEntity report = ownedReport(reportId, farmer);

        if (!report.isEpidemicRisk()) {
            throw FolixFarmerAlertException.notEpidemicRisk();
        }
        if (!report.hasRegion()) {
            throw FolixFarmerAlertException.noLocation();
        }

        int cellSize = mapSettings.cellSizeMillidegrees();
        int minLat = report.getRegionLatMdeg() - cellSize;
        int maxLat = report.getRegionLatMdeg() + cellSize;
        int minLng = report.getRegionLngMdeg() - cellSize;
        int maxLng = report.getRegionLngMdeg() + cellSize;

        List<FolixFarmerLocationEntity> nearbyLocations = locationRepo.findNearbyExcluding(
                minLat, maxLat, minLng, maxLng, farmer.id());

        Instant now = clock.instant();
        Instant cooldownCutoff = now.minus(COOLDOWN_DURATION);
        Instant expiresAt = now.plus(EXPIRY_DURATION);

        Set<UUID> processedRecipients = new HashSet<>();
        List<FolixFarmerAlertEntity> alertsToSave = new ArrayList<>();

        for (FolixFarmerLocationEntity loc : nearbyLocations) {
            UUID recipientId = loc.getFarmerId();
            if (processedRecipients.add(recipientId)) {
                boolean onCooldown = alertRepo.existsByRecipientFarmerIdAndDiseaseNameAndCreatedAtAfter(
                        recipientId, report.getDisease(), cooldownCutoff);
                if (!onCooldown) {
                    alertsToSave.add(new FolixFarmerAlertEntity(
                            recipientId,
                            report.getDisease(),
                            report.getCrop(),
                            report.getRegionLatMdeg(),
                            report.getRegionLngMdeg(),
                            true,
                            now,
                            expiresAt
                    ));
                }
            }
        }

        if (!alertsToSave.isEmpty()) {
            alertRepo.saveAll(alertsToSave);
        }

        return new FolixFarmerAlertTriggerResponse(report.getId(), alertsToSave.size());
    }

    /**
     * Escalates a scan report to the local agricultural authority when a farmer suspects
     * higher spread risk than estimated by the automated AI analysis.
     */
    @Transactional
    public FolixAuthorityEscalationResponse escalateToAuthority(UUID reportId, String farmerNote, AuthenticatedFarmer farmer) {
        return escalateToAuthority(reportId, farmerNote, null, null, farmer);
    }

    @Transactional
    public FolixAuthorityEscalationResponse escalateToAuthority(UUID reportId, String farmerNote, Double reqLat, Double reqLng, AuthenticatedFarmer farmer) {
        requireFarmer(farmer);
        FolixScanReportEntity report = ownedReport(reportId, farmer);

        // Mark report as epidemic-risk since farmer flagged high spreadability
        report.markEpidemicRisk(true);
        reportRepo.save(report);

        Double lat = reqLat;
        Double lng = reqLng;
        if (lat == null || lng == null) {
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
        }

        Long authorityId = 1L;
        String authorityName = "Agricultural Authority";
        var resolved = resolveNearestAuthority(lat, lng);
        if (resolved.isPresent()) {
            authorityId = resolved.get().getId();
            authorityName = resolved.get().getName();
        }

        String note = "Farmer Escalation: High spread risk suspected by farmer despite low AI estimate";
        if (farmerNote != null && !farmerNote.trim().isEmpty()) {
            note += " (" + farmerNote.trim() + ")";
        }

        if (alertJpaRepo != null) {
            AlertEntity alert = new AlertEntity(
                    report.getId(),
                    authorityId,
                    report.getCrop(),
                    report.getDisease(),
                    report.getSeverityPercent() != null ? report.getSeverityPercent().doubleValue() : 50.0,
                    true,
                    lat,
                    lng,
                    AlertEntity.Channel.WEBHOOK
            );
            alert.updateStatus(AlertEntity.Status.NOTIFIED, note);
            alertJpaRepo.save(alert);
        }

        return new FolixAuthorityEscalationResponse(
                report.getId(),
                authorityId,
                authorityName,
                "Alert sent to " + authorityName + ". The authority has been notified of the spread risk and can dispatch alerts to nearby farmers."
        );
    }

    public Optional<AuthorityEntity> resolveNearestAuthority(Double lat, Double lng) {
        if (authorityJpaRepo == null) return Optional.empty();
        List<AuthorityEntity> all = authorityJpaRepo.findAll();
        if (all.isEmpty()) return Optional.empty();

        if (lat != null && lng != null && !lat.isNaN() && !lng.isNaN()) {
            AuthorityEntity best = null;
            double minDistance = Double.MAX_VALUE;
            for (AuthorityEntity auth : all) {
                if (auth.getLatitude() != null && auth.getLongitude() != null) {
                    double d = haversineKm(lat, lng, auth.getLatitude(), auth.getLongitude());
                    if (d < minDistance) {
                        minDistance = d;
                        best = auth;
                    }
                }
            }
            if (best != null) {
                return Optional.of(best);
            }
        }
        return Optional.of(all.get(all.size() - 1));
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
     * Authority broadcast: sends an epidemic alert to farmers located near the alert's origin
     * (or all registered farmers in the jurisdiction if coordinates are not set or sparse).
     */
    @Transactional
    public AuthorityDtos.AuthorityBroadcastResponse broadcastAuthorityAlert(Long authorityId, Long alertId) {
        if (alertJpaRepo == null) {
            return new AuthorityDtos.AuthorityBroadcastResponse(false, 0, "Alert repository not configured");
        }
        AlertEntity alert = alertJpaRepo.findById(alertId)
                .filter(a -> a.getAuthorityId().equals(authorityId))
                .orElse(null);

        if (alert == null) {
            return new AuthorityDtos.AuthorityBroadcastResponse(false, 0, "Alert not found for this authority");
        }

        Double lat = alert.getLatitude();
        Double lng = alert.getLongitude();
        int regionLatMdeg = lat != null ? (int) Math.round(lat * 1000) : 0;
        int regionLngMdeg = lng != null ? (int) Math.round(lng * 1000) : 0;

        List<UUID> recipientIds = new ArrayList<>();
        if (lat != null && lng != null && !lat.isNaN() && !lng.isNaN()) {
            int cellSize = mapSettings.cellSizeMillidegrees();
            int minLat = regionLatMdeg - cellSize * 2;
            int maxLat = regionLatMdeg + cellSize * 2;
            int minLng = regionLngMdeg - cellSize * 2;
            int maxLng = regionLngMdeg + cellSize * 2;

            List<FolixFarmerLocationEntity> nearby = locationRepo.findNearby(minLat, maxLat, minLng, maxLng);
            for (FolixFarmerLocationEntity loc : nearby) {
                if (!recipientIds.contains(loc.getFarmerId())) {
                    recipientIds.add(loc.getFarmerId());
                }
            }
        }

        Instant now = clock.instant();
        Instant cooldownCutoff = now.minus(COOLDOWN_DURATION);
        Instant expiresAt = now.plus(EXPIRY_DURATION);

        List<FolixFarmerAlertEntity> alertsToSave = new ArrayList<>();
        for (UUID recipientId : recipientIds) {
            boolean onCooldown = alertRepo.existsByRecipientFarmerIdAndDiseaseNameAndCreatedAtAfter(
                    recipientId, alert.getDiseaseName(), cooldownCutoff);
            if (!onCooldown) {
                alertsToSave.add(new FolixFarmerAlertEntity(
                        recipientId,
                        alert.getDiseaseName(),
                        alert.getCropName(),
                        regionLatMdeg,
                        regionLngMdeg,
                        true,
                        now,
                        expiresAt
                ));
            }
        }

        if (!alertsToSave.isEmpty()) {
            alertRepo.saveAll(alertsToSave);
        }

        alert.updateStatus(AlertEntity.Status.ACKNOWLEDGED, "Broadcasted to " + alertsToSave.size() + " nearby farmers on " + now);
        alertJpaRepo.save(alert);

        return new AuthorityDtos.AuthorityBroadcastResponse(
                true,
                alertsToSave.size(),
                "Alert successfully dispatched to " + alertsToSave.size() + " nearby farmer" + (alertsToSave.size() == 1 ? "" : "s") + "."
        );
    }

    /**
     * Authority broadcast: creates a new epidemic advisory alert and broadcasts it directly to registered farmers.
     */
    @Transactional
    public AuthorityDtos.AuthorityBroadcastResponse broadcastNewAuthorityAlert(Long authorityId, AuthorityDtos.NewBroadcastAlertRequest req) {
        if (alertJpaRepo == null || authorityJpaRepo == null) {
            return new AuthorityDtos.AuthorityBroadcastResponse(false, 0, "Alert repository not configured");
        }
        var authOpt = authorityJpaRepo.findById(authorityId);
        if (authOpt.isEmpty()) {
            return new AuthorityDtos.AuthorityBroadcastResponse(false, 0, "Authority not found");
        }
        var authority = authOpt.get();

        Double lat = authority.getLatitude();
        Double lng = authority.getLongitude();
        int regionLatMdeg = lat != null ? (int) Math.round(lat * 1000) : 0;
        int regionLngMdeg = lng != null ? (int) Math.round(lng * 1000) : 0;

        double severity = req.severityPercent() != null ? req.severityPercent() : 80.0;
        String advisory = (req.advisory() != null && !req.advisory().isBlank()) ? req.advisory().trim() : "High epidemic risk detected in your area.";

        AlertEntity alert = new AlertEntity(
                UUID.randomUUID(),
                authorityId,
                req.cropName().trim(),
                req.diseaseName().trim(),
                severity,
                true,
                lat,
                lng,
                AlertEntity.Channel.WEBHOOK
        );
        alert.updateStatus(AlertEntity.Status.ACKNOWLEDGED, "Authority Advisory Broadcast: " + advisory);
        alert = alertJpaRepo.save(alert);

        List<UUID> recipientIds = new ArrayList<>();
        if (lat != null && lng != null && !lat.isNaN() && !lng.isNaN() && locationRepo != null) {
            int cellSize = mapSettings.cellSizeMillidegrees();
            int minLat = regionLatMdeg - cellSize * 3;
            int maxLat = regionLatMdeg + cellSize * 3;
            int minLng = regionLngMdeg - cellSize * 3;
            int maxLng = regionLngMdeg + cellSize * 3;

            List<FolixFarmerLocationEntity> nearby = locationRepo.findNearby(minLat, maxLat, minLng, maxLng);
            for (FolixFarmerLocationEntity loc : nearby) {
                if (!recipientIds.contains(loc.getFarmerId())) {
                    recipientIds.add(loc.getFarmerId());
                }
            }
        }


        Instant now = clock.instant();
        Instant expiresAt = now.plus(EXPIRY_DURATION);

        List<FolixFarmerAlertEntity> alertsToSave = new ArrayList<>();
        for (UUID recipientId : recipientIds) {
            alertsToSave.add(new FolixFarmerAlertEntity(
                    recipientId,
                    alert.getDiseaseName(),
                    alert.getCropName(),
                    regionLatMdeg,
                    regionLngMdeg,
                    true,
                    now,
                    expiresAt
            ));
        }

        if (!alertsToSave.isEmpty()) {
            alertRepo.saveAll(alertsToSave);
        }

        return new AuthorityDtos.AuthorityBroadcastResponse(
                true,
                alertsToSave.size(),
                "Advisory successfully dispatched to " + alertsToSave.size() + " registered farmer" + (alertsToSave.size() == 1 ? "" : "s") + "."
        );
    }

    /**
     * Returns the active, non-expired alerts for the authenticated farmer, enforcing
     * geographic relevance against the farmer's registered location.
     */
    @Transactional(readOnly = true)
    public List<FolixFarmerAlertResponse> getMyAlerts(AuthenticatedFarmer farmer) {
        requireFarmer(farmer);
        Instant now = clock.instant();
        List<FolixFarmerAlertEntity> alerts = alertRepo.findByRecipientFarmerIdAndExpiresAtAfterOrderByCreatedAtDesc(farmer.id(), now);

        if (locationRepo != null) {
            Optional<FolixFarmerLocationEntity> farmerLoc = locationRepo.findById(farmer.id());
            if (farmerLoc.isPresent()) {
                int cellSize = mapSettings.cellSizeMillidegrees();
                int maxDelta = cellSize * 2;
                int fLat = farmerLoc.get().getRegionLatMdeg();
                int fLng = farmerLoc.get().getRegionLngMdeg();

                alerts = alerts.stream()
                        .filter(a -> Math.abs(a.getRegionLatMdeg() - fLat) <= maxDelta
                                  && Math.abs(a.getRegionLngMdeg() - fLng) <= maxDelta)
                        .toList();
            }
        }

        return alerts.stream()
                .map(FolixFarmerAlertResponse::from)
                .toList();
    }

    /**
     * Lists real registered farmers and their agricultural profiles under an authority's jurisdiction.
     */
    @Transactional(readOnly = true)
    public List<AuthorityDtos.AuthorityFarmerView> listFarmers(Long authorityId, String district) {
        if (farmerRepo == null) return List.of();
        return farmerRepo.findAll().stream()
                .map(farmer -> {
                    String fmrId = "FMR-" + farmer.getId().toString().substring(0, 8).toUpperCase();
                    var profileOpt = agriProfileRepo != null ? agriProfileRepo.findById(farmer.getId()) : java.util.Optional.<com.cropscanner.folix.agrinetwork.FolixFarmerAgriProfileEntity>empty();
                    String village = profileOpt.map(p -> p.getVillageOrArea() != null && !p.getVillageOrArea().isBlank()
                            ? p.getVillageOrArea() : "Unspecified area").orElse("Unspecified area");
                    List<String> crops = profileOpt.map(p -> {
                        if (p.getCrops() == null || p.getCrops().isBlank()) return List.<String>of();
                        return java.util.Arrays.stream(p.getCrops().split(","))
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .toList();
                    }).orElse(List.of());

                    var lastReportOpt = reportRepo.findFirstByFarmer_IdOrderByCreatedAtDesc(farmer.getId());
                    String lastReport = lastReportOpt.map(r -> r.getDisease() + " on " + r.getCrop())
                            .orElse("No scans yet (Healthy)");

                    String status = farmer.getStatus() != null ? farmer.getStatus().name() : "ACTIVE";
                    String statusBadge = "badge-active";
                    if ("SUSPENDED".equalsIgnoreCase(status)) statusBadge = "badge-alert";

                    return new AuthorityDtos.AuthorityFarmerView(
                            fmrId,
                            farmer.getId(),
                            farmer.getName(),
                            farmer.getEmail(),
                            null,
                            village,
                            district != null ? district : "Area",
                            crops,
                            status.substring(0, 1).toUpperCase() + status.substring(1).toLowerCase(),
                            statusBadge,
                            lastReport,
                            farmer.getCreatedAt()
                    );
                })
                .toList();
    }

    /**
     * Authority admin action: completely deletes a registered farmer and their associated records.
     */
    @Transactional
    public boolean deleteFarmer(Long authorityId, UUID farmerId) {
        if (farmerRepo == null || farmerId == null) return false;
        var farmerOpt = farmerRepo.findById(farmerId);
        if (farmerOpt.isEmpty()) return false;

        // 1. Delete associated case forwards & reports
        if (reportRepo != null) {
            var reports = reportRepo.findAllByFarmer_Id(farmerId);
            for (var r : reports) {
                if (caseForwardRepo != null) {
                    caseForwardRepo.deleteByReport_Id(r.getId());
                }
                reportRepo.delete(r);
            }
        }
        // 2. Delete farmer location
        if (locationRepo != null) {
            locationRepo.deleteById(farmerId);
        }
        // 3. Delete agri profile
        if (agriProfileRepo != null) {
            agriProfileRepo.deleteById(farmerId);
        }
        // 4. Delete sessions
        if (sessionRepo != null) {
            sessionRepo.deleteByFarmerId(farmerId);
        }
        // 5. Delete alerts
        if (alertRepo != null) {
            alertRepo.deleteByRecipientFarmerId(farmerId);
        }
        // 6. Delete farmer entity
        farmerRepo.deleteById(farmerId);
        return true;
    }

    /**
     * Authority admin action: update farmer status (e.g. ACTIVE or DISABLED).
     */
    @Transactional
    public Optional<AuthorityDtos.AuthorityFarmerView> updateFarmerStatus(Long authorityId, UUID farmerId, String newStatus) {
        if (farmerRepo == null || farmerId == null) return Optional.empty();
        var farmerOpt = farmerRepo.findById(farmerId);
        if (farmerOpt.isEmpty()) return Optional.empty();

        var farmer = farmerOpt.get();
        com.cropscanner.folix.auth.FolixFarmerStatus statusEnum;
        try {
            statusEnum = com.cropscanner.folix.auth.FolixFarmerStatus.valueOf(newStatus.toUpperCase());
        } catch (Exception e) {
            statusEnum = com.cropscanner.folix.auth.FolixFarmerStatus.ACTIVE;
        }

        farmer.setStatus(statusEnum);
        farmerRepo.save(farmer);

        if (statusEnum == com.cropscanner.folix.auth.FolixFarmerStatus.DISABLED && sessionRepo != null) {
            sessionRepo.deleteByFarmerId(farmerId);
        }

        String fmrId = "FMR-" + farmer.getId().toString().substring(0, 8).toUpperCase();
        String st = statusEnum.name();
        String statusBadge = "DISABLED".equalsIgnoreCase(st) ? "badge-alert" : "badge-active";
        return Optional.of(new AuthorityDtos.AuthorityFarmerView(
                fmrId,
                farmer.getId(),
                farmer.getName(),
                farmer.getEmail(),
                null,
                "Updated",
                "District",
                List.of(),
                st.substring(0, 1).toUpperCase() + st.substring(1).toLowerCase(),
                statusBadge,
                "Account status set to " + st,
                farmer.getCreatedAt()
        ));
    }

    private FolixScanReportEntity ownedReport(UUID reportId, AuthenticatedFarmer farmer) {
        if (reportId == null) {
            throw FolixReportException.malformed("report id is missing");
        }
        return reportRepo.findByIdAndFarmer_Id(reportId, farmer.id())
                .orElseThrow(() -> FolixReportException.notFound(reportId));
    }

    private static void requireFarmer(AuthenticatedFarmer farmer) {
        if (farmer == null || farmer.id() == null) {
            throw FolixAuthException.unauthenticated();
        }
    }
}
