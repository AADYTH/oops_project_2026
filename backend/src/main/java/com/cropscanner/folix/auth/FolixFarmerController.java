package com.cropscanner.folix.auth;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/farmer — the authenticated farmer's own account. Stage 6A: the basic profile foundation only
 * (name, email, member-since). The whole route is under {@link FolixWebConfig#PROTECTED_PATHS}, so an
 * anonymous request is answered 401 before this class is reached.
 *
 * Which farmer? The one the server resolved from the session cookie ({@link AuthenticatedFarmer}) —
 * there is no id in the URL, query or body that could name someone else.
 */
@RestController
@RequestMapping("/api/farmer")
@CrossOrigin(origins = "${cropscanner.cors.allowed-origin:http://localhost:5173}", allowCredentials = "true")
public class FolixFarmerController {

    @GetMapping("/profile")
    public ResponseEntity<FarmerProfileResponse> profile(AuthenticatedFarmer farmer) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(FarmerProfileResponse.from(farmer));
    }
}
