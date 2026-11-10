package com.cropscanner.controller;

import com.cropscanner.dto.AuthorityDtos;
import com.cropscanner.entity.AlertEntity;
import com.cropscanner.entity.AuthorityEntity;
import com.cropscanner.folix.forwarding.FolixCaseForwardRepository;
import com.cropscanner.service.AlertService;
import com.cropscanner.service.AuthoritySelfServiceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Authority self-registration and dashboard endpoints (Tier 3 upgrade #9).
 * Auth here is a per-authority API key issued at registration time, passed
 * as {@code X-Authority-Api-Key} — separate from the general
 * {@code ApiProtectionFilter}'s optional app-wide key, since these
 * endpoints need to identify *which* authority is calling, not just
 * whether the caller holds a shared secret.
 */
@RestController
@RequestMapping("/api/v1/authorities")
@CrossOrigin(origins = "${cropscanner.cors.allowed-origin:http://localhost:5173}")
public class AuthorityController {

    private final AuthoritySelfServiceService selfServiceService;
    private final AlertService alertService;
    private final FolixCaseForwardRepository caseForwardRepository;
    private final com.cropscanner.folix.farmeralert.FolixFarmerAlertService farmerAlertService;

    public AuthorityController(AuthoritySelfServiceService selfServiceService, AlertService alertService,
                                FolixCaseForwardRepository caseForwardRepository,
                                com.cropscanner.folix.farmeralert.FolixFarmerAlertService farmerAlertService) {
        this.selfServiceService = selfServiceService;
        this.alertService = alertService;
        this.caseForwardRepository = caseForwardRepository;
        this.farmerAlertService = farmerAlertService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthorityDtos.RegistrationResponse> register(@Valid @RequestBody AuthorityDtos.RegistrationRequest req) {
        return ResponseEntity.ok(selfServiceService.register(req));
    }

    @PutMapping("/{id}/contact")
    public ResponseEntity<?> updateContact(
            @PathVariable Long id,
            @RequestHeader("X-Authority-Api-Key") String apiKey,
            @RequestBody AuthorityDtos.ContactUpdateRequest req) {

        Optional<AuthorityEntity> authority = selfServiceService.findByIdAndApiKey(id, apiKey);
        if (authority.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        selfServiceService.updateContactInfo(authority.get(), req);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/alerts")
    public ResponseEntity<List<AuthorityDtos.AlertView>> listAlerts(
            @PathVariable Long id, @RequestHeader("X-Authority-Api-Key") String apiKey) {

        if (selfServiceService.findByIdAndApiKey(id, apiKey).isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        List<AuthorityDtos.AlertView> alerts = alertService.findAlertsForAuthority(id).stream()
                .map(AuthorityDtos.AlertView::from)
                .toList();
        return ResponseEntity.ok(alerts);
    }

    @PatchMapping("/{id}/alerts/{alertId}")
    public ResponseEntity<AuthorityDtos.AlertView> updateAlert(
            @PathVariable Long id,
            @PathVariable Long alertId,
            @RequestHeader("X-Authority-Api-Key") String apiKey,
            @Valid @RequestBody AuthorityDtos.AlertStatusUpdateRequest req) {

        if (selfServiceService.findByIdAndApiKey(id, apiKey).isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        AlertEntity.Status status;
        try {
            status = AlertEntity.Status.valueOf(req.status().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        return alertService.updateAlertStatus(id, alertId, status, req.note())
                .map(AuthorityDtos.AlertView::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}/alerts/{alertId}")
    public ResponseEntity<Void> deleteAlert(
            @PathVariable Long id,
            @PathVariable Long alertId,
            @RequestHeader("X-Authority-Api-Key") String apiKey) {

        if (selfServiceService.findByIdAndApiKey(id, apiKey).isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (alertService.deleteAlert(id, alertId)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * Stage 6E Part 2B — cases farmers have manually forwarded (Stage 6E Part 1), for the Authority
     * Dashboard's read-only "forwarded cases" section. Same {@code X-Authority-Api-Key} gate as the other
     * endpoints here; forwarded cases are not yet tied to a specific authority/support contact account
     * (the Agricultural Network directory in {@code folix.agrinetwork} is a separate, unauthenticated
     * concept from this authority login — see {@code FolixSupportContactEntity}'s class doc), so any
     * logged-in authority sees the same list. Nothing here creates, changes or notifies anything.
     */
    @GetMapping("/{id}/forwarded-cases")
    public ResponseEntity<List<AuthorityDtos.ForwardedCaseView>> listForwardedCases(
            @PathVariable Long id, @RequestHeader("X-Authority-Api-Key") String apiKey) {

        if (selfServiceService.findByIdAndApiKey(id, apiKey).isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        List<AuthorityDtos.ForwardedCaseView> cases = caseForwardRepository
                .findAllWithReportAndContactOrderByForwardedAtDesc().stream()
                .map(AuthorityDtos.ForwardedCaseView::from)
                .toList();
        return ResponseEntity.ok(cases);
    }

    /** The full report behind one forwarded case ("view details"). 404 if that report was never forwarded. */
    @GetMapping("/{id}/forwarded-cases/{reportId}")
    public ResponseEntity<AuthorityDtos.ForwardedCaseDetail> forwardedCaseDetail(
            @PathVariable Long id, @PathVariable UUID reportId,
            @RequestHeader("X-Authority-Api-Key") String apiKey) {

        if (selfServiceService.findByIdAndApiKey(id, apiKey).isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return caseForwardRepository.findByReport_IdWithReportAndContact(reportId)
                .map(AuthorityDtos.ForwardedCaseDetail::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}/forwarded-cases/{reportId}")
    public ResponseEntity<Void> deleteForwardedCase(
            @PathVariable Long id,
            @PathVariable UUID reportId,
            @RequestHeader("X-Authority-Api-Key") String apiKey) {

        if (selfServiceService.findByIdAndApiKey(id, apiKey).isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        caseForwardRepository.deleteByReport_Id(reportId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Authority broadcast: sends the epidemic alert directly to nearby registered farmers.
     */
    @PostMapping("/{id}/alerts/{alertId}/broadcast")
    public ResponseEntity<AuthorityDtos.AuthorityBroadcastResponse> broadcastAlert(
            @PathVariable Long id,
            @PathVariable Long alertId,
            @RequestHeader("X-Authority-Api-Key") String apiKey) {

        if (selfServiceService.findByIdAndApiKey(id, apiKey).isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(farmerAlertService.broadcastAuthorityAlert(id, alertId));
    }

    /**
     * Authority broadcast: directly broadcasts a new epidemic advisory alert to nearby registered farmers.
     */
    @PostMapping("/{id}/alerts/broadcast-new")
    public ResponseEntity<AuthorityDtos.AuthorityBroadcastResponse> broadcastNewAlert(
            @PathVariable Long id,
            @RequestHeader("X-Authority-Api-Key") String apiKey,
            @Valid @RequestBody AuthorityDtos.NewBroadcastAlertRequest req) {

        if (selfServiceService.findByIdAndApiKey(id, apiKey).isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(farmerAlertService.broadcastNewAuthorityAlert(id, req));
    }

    /**
     * Lists real registered farmers and their agricultural profiles under this authority's jurisdiction.
     * No fake or mock data is ever returned.
     */
    @GetMapping("/{id}/farmers")
    public ResponseEntity<List<AuthorityDtos.AuthorityFarmerView>> listFarmers(
            @PathVariable Long id,
            @RequestHeader("X-Authority-Api-Key") String apiKey) {

        var authorityOpt = selfServiceService.findByIdAndApiKey(id, apiKey);
        if (authorityOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        var authority = authorityOpt.get();
        return ResponseEntity.ok(farmerAlertService.listFarmers(id, authority.getDistrict()));
    }

    @DeleteMapping("/{id}/farmers/{farmerId}")
    public ResponseEntity<Void> deleteFarmer(
            @PathVariable Long id,
            @PathVariable UUID farmerId,
            @RequestHeader("X-Authority-Api-Key") String apiKey) {

        if (selfServiceService.findByIdAndApiKey(id, apiKey).isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (farmerAlertService.deleteFarmer(id, farmerId)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    @PatchMapping("/{id}/farmers/{farmerId}/status")
    public ResponseEntity<AuthorityDtos.AuthorityFarmerView> updateFarmerStatus(
            @PathVariable Long id,
            @PathVariable UUID farmerId,
            @RequestHeader("X-Authority-Api-Key") String apiKey,
            @RequestBody AuthorityDtos.FarmerStatusUpdateRequest req) {

        if (selfServiceService.findByIdAndApiKey(id, apiKey).isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return farmerAlertService.updateFarmerStatus(id, farmerId, req.status())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
