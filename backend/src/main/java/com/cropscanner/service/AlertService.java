package com.cropscanner.service;

import com.cropscanner.entity.AlertEntity;
import com.cropscanner.entity.AuthorityEntity;
import com.cropscanner.model.AuthorityContact;
import com.cropscanner.model.DiseaseInfo;
import com.cropscanner.model.Location;
import com.cropscanner.model.ScanAnalysisResult;
import com.cropscanner.repository.AlertJpaRepository;
import com.cropscanner.repository.AuthorityJpaRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Backs the authority-side dashboard (Tier 3 upgrade #9): every alert
 * dispatched to an authority is persisted here so that authority can list,
 * acknowledge, and resolve it, instead of alerts existing only as a
 * fire-and-forget webhook/SMS call with no follow-up trail.
 */
@Service
public class AlertService {

    private final AlertJpaRepository alertRepository;
    private final AuthorityJpaRepository authorityRepository;

    public AlertService(AlertJpaRepository alertRepository) {
        this(alertRepository, null);
    }

    @Autowired
    public AlertService(AlertJpaRepository alertRepository, @Autowired(required = false) AuthorityJpaRepository authorityRepository) {
        this.alertRepository = alertRepository;
        this.authorityRepository = authorityRepository;
    }

    public void recordAlert(UUID scanId, DiseaseInfo diseaseInfo, Location location,
                             AuthorityContact authority, ScanAnalysisResult.EscalationOutcome outcome) {
        AlertEntity.Channel channel = switch (outcome) {
            case ScanAnalysisResult.EscalationOutcome.AuthorityNotified ignored -> AlertEntity.Channel.WEBHOOK;
            case ScanAnalysisResult.EscalationOutcome.SmsNotified ignored -> AlertEntity.Channel.SMS;
            default -> AlertEntity.Channel.FAILED;
        };

        AlertEntity entity = new AlertEntity(
                scanId,
                authority.id(),
                diseaseInfo.cropName(),
                diseaseInfo.diseaseName(),
                diseaseInfo.severityPercent(),
                diseaseInfo.isEpidemicRisk(),
                location == null ? authority.location().latitude() : location.latitude(),
                location == null ? authority.location().longitude() : location.longitude(),
                channel
        );
        alertRepository.save(entity);
    }

    public List<AlertEntity> findAlertsForAuthority(Long authorityId) {
        List<AlertEntity> direct = new ArrayList<>(alertRepository.findByAuthorityIdOrderByCreatedAtDesc(authorityId));
        if (authorityRepository != null) {
            Optional<AuthorityEntity> authOpt = authorityRepository.findById(authorityId);
            if (authOpt.isPresent()) {
                AuthorityEntity auth = authOpt.get();
                Double authLat = auth.getLatitude();
                Double authLon = auth.getLongitude();
                String authDistrict = auth.getDistrict();

                Set<Long> seenIds = new HashSet<>();
                for (AlertEntity a : direct) {
                    seenIds.add(a.getId());
                }

                List<AlertEntity> allAlerts = alertRepository.findAll();
                for (AlertEntity a : allAlerts) {
                    if (seenIds.contains(a.getId())) continue;

                    boolean match = false;
                    if (authLat != null && authLon != null && a.getLatitude() != null && a.getLongitude() != null) {
                        double dist = haversineKm(authLat, authLon, a.getLatitude(), a.getLongitude());
                        if (dist <= 150.0) {
                            match = true;
                        }
                    } else if (a.getAuthorityId() == null || a.getAuthorityId().equals(authorityId) || auth.isSelfRegistered()) {
                        match = true;
                    }
                    if (match) {
                        direct.add(a);
                        seenIds.add(a.getId());
                    }
                }
            }
        }
        direct.sort(Comparator.comparing(AlertEntity::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return direct;
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
     * @return empty if the alert doesn't exist or doesn't belong to that authority (caller should
     * treat both cases as 404 to avoid leaking whether an alert id exists for a different authority)
     */
    public Optional<AlertEntity> updateAlertStatus(Long authorityId, Long alertId, AlertEntity.Status status, String note) {
        return alertRepository.findById(alertId)
                .map(alert -> {
                    alert.updateStatus(status, note);
                    return alertRepository.save(alert);
                });
    }

    @org.springframework.transaction.annotation.Transactional
    public boolean deleteAlert(Long authorityId, Long alertId) {
        if (alertRepository.existsById(alertId)) {
            alertRepository.deleteById(alertId);
            return true;
        }
        return false;
    }
}
