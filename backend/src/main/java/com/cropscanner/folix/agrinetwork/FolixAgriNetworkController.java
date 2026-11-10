package com.cropscanner.folix.agrinetwork;

import com.cropscanner.folix.auth.AuthenticatedFarmer;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * /api/farmer/agri-network — the Agricultural Network (Stage 6D): the authenticated farmer's own
 * agricultural profile, plus the read-only agricultural support directory.
 *
 * The whole route sits under {@code /api/farmer/**}, which {@code FolixWebConfig.PROTECTED_PATHS} already
 * requires a valid session for (401 otherwise) and which gets the CSRF Origin check on the PUT. Whose
 * profile is read or changed is decided ONLY by the {@link AuthenticatedFarmer} resolved from the session
 * cookie — there is no farmer id in the URL, query or body. Thin wrapper: all rules live in
 * {@link FolixAgriNetworkService}.
 *
 * There is deliberately no endpoint here that sends, forwards or assigns anything — that is Stage 6E.
 */
@RestController
@RequestMapping("/api/farmer/agri-network")
@CrossOrigin(origins = "${cropscanner.cors.allowed-origin:http://localhost:5173}", allowCredentials = "true")
public class FolixAgriNetworkController {

    private final FolixAgriNetworkService service;

    public FolixAgriNetworkController(FolixAgriNetworkService service) {
        this.service = service;
    }

    @GetMapping("/profile")
    public ResponseEntity<FolixAgriProfileResponse> profile(AuthenticatedFarmer farmer) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.getProfile(farmer));
    }

    @PutMapping("/profile")
    public ResponseEntity<FolixAgriProfileResponse> saveProfile(@RequestBody(required = false) FolixAgriProfileRequest request,
                                                                 AuthenticatedFarmer farmer) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.saveProfile(request, farmer));
    }

    @GetMapping("/directory")
    public ResponseEntity<List<FolixSupportContactResponse>> directory(AuthenticatedFarmer farmer) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.directory(farmer));
    }
}
