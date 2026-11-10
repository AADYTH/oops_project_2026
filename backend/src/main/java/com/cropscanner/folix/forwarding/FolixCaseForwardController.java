package com.cropscanner.folix.forwarding;

import com.cropscanner.folix.auth.AuthenticatedFarmer;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * /api/scan/reports/{id}/forward — manual case forwarding (Stage 6E Part 1).
 *
 * The route sits under {@code /api/scan/reports/**}, which {@code FolixWebConfig.PROTECTED_PATHS} already
 * requires a valid session for (401 otherwise) and which gets the CSRF Origin check on the POST. Who is
 * forwarding is decided ONLY by the {@link AuthenticatedFarmer} resolved from the session cookie — there is
 * no farmer id in the URL, query or body. Thin wrapper: all rules live in {@link FolixCaseForwardService}.
 */
@RestController
@RequestMapping("/api/scan/reports/{id}/forward")
@CrossOrigin(origins = "${cropscanner.cors.allowed-origin:http://localhost:5173}", allowCredentials = "true")
public class FolixCaseForwardController {

    private final FolixCaseForwardService service;

    public FolixCaseForwardController(FolixCaseForwardService service) {
        this.service = service;
    }

    /** Explicitly forwards the caller's own report to the chosen support contact. 201, or 409 if already forwarded. */
    @PostMapping
    public ResponseEntity<FolixCaseForwardResponse> forward(@PathVariable("id") UUID id,
                                                            @RequestBody(required = false) FolixCaseForwardRequest request,
                                                            AuthenticatedFarmer farmer) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.forward(id, request, farmer));
    }

    /** The report's forwarding state (NOT_FORWARDED, or FORWARDED with contact and timestamp). */
    @GetMapping
    public ResponseEntity<FolixCaseForwardResponse> status(@PathVariable("id") UUID id, AuthenticatedFarmer farmer) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.status(id, farmer));
    }
}
