package com.cropscanner.folix.location;

import com.cropscanner.folix.auth.AuthenticatedFarmer;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/farmer/location — the authenticated farmer's own, optional, revocable location sharing (Stage 6C).
 *
 * The route sits under {@code /api/farmer/**}, which {@code FolixWebConfig.PROTECTED_PATHS} already requires a
 * session for (401 otherwise) and which gets the CSRF Origin check on PUT/DELETE. Whose location is affected is
 * decided ONLY by the {@link AuthenticatedFarmer} resolved from the session cookie — there is no id in the URL,
 * query or body. Thin wrapper: all rules live in {@link FolixLocationService}.
 */
@RestController
@RequestMapping("/api/farmer/location")
@CrossOrigin(origins = "${cropscanner.cors.allowed-origin:http://localhost:5173}", allowCredentials = "true")
public class FolixLocationController {

    private final FolixLocationService locationService;

    public FolixLocationController(FolixLocationService locationService) {
        this.locationService = locationService;
    }

    @GetMapping
    public ResponseEntity<FolixLocationResponse> status(AuthenticatedFarmer farmer) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(locationService.status(farmer));
    }

    /** Turn sharing on (or refresh the region). Body: {@code {"consent": true, "latitude": .., "longitude": ..}}. */
    @PutMapping
    public ResponseEntity<FolixLocationResponse> enable(@RequestBody(required = false) FolixLocationRequest request,
                                                        AuthenticatedFarmer farmer) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(locationService.enable(request, farmer));
    }

    /** Turn sharing off and stop contributing to the map. */
    @DeleteMapping
    public ResponseEntity<FolixLocationResponse> disable(AuthenticatedFarmer farmer) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(locationService.disable(farmer));
    }
}
