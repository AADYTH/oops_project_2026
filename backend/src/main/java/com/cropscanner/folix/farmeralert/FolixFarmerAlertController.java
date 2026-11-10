package com.cropscanner.folix.farmeralert;

import com.cropscanner.folix.auth.AuthenticatedFarmer;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Endpoints for farmer-to-farmer epidemic alerts (FOLIX Stage 6I).
 *
 * Sits under /api/farmer/**, protected by session authentication and CSRF origin verification.
 */
@RestController
@RequestMapping("/api/farmer/alerts")
@CrossOrigin(origins = "${cropscanner.cors.allowed-origin:http://localhost:5173}", allowCredentials = "true")
public class FolixFarmerAlertController {

    private final FolixFarmerAlertService alertService;

    public FolixFarmerAlertController(FolixFarmerAlertService alertService) {
        this.alertService = alertService;
    }

    /**
     * Explicitly triggers epidemic alerts to nearby farmers from an owned report.
     */
    @PostMapping("/trigger/{reportId}")
    public ResponseEntity<FolixFarmerAlertTriggerResponse> triggerAlerts(
            @PathVariable("reportId") UUID reportId,
            AuthenticatedFarmer farmer) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(alertService.triggerAlerts(reportId, farmer));
    }

    /**
     * Escalates a scan report to the local agricultural authority when a farmer suspects
     * higher spread risk than estimated by the automated AI analysis.
     */
    @PostMapping("/escalate-to-authority/{reportId}")
    public ResponseEntity<FolixAuthorityEscalationResponse> escalateToAuthority(
            @PathVariable("reportId") UUID reportId,
            @org.springframework.web.bind.annotation.RequestBody(required = false) FolixAuthorityEscalationRequest request,
            AuthenticatedFarmer farmer) {
        String note = request != null ? request.note() : null;
        Double lat = request != null ? request.latitude() : null;
        Double lng = request != null ? request.longitude() : null;
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(alertService.escalateToAuthority(reportId, note, lat, lng, farmer));
    }

    /**
     * Lists active (non-expired) epidemic alerts received by the authenticated farmer.
     */
    @GetMapping
    public ResponseEntity<List<FolixFarmerAlertResponse>> getMyAlerts(
            AuthenticatedFarmer farmer) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(alertService.getMyAlerts(farmer));
    }
}
