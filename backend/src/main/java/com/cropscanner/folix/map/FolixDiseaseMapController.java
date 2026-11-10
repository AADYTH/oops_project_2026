package com.cropscanner.folix.map;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/folix/disease-map — aggregated, privacy-filtered regional disease information (Stage 6C).
 *
 * Public on purpose (like the legacy outbreak heat map it replaces on the Disease Map page): it can be public
 * because it returns only {@link FolixDiseaseMapResponse} — no farmer identity, no session data, no report ids,
 * no exact coordinates, and no region that did not meet the minimum-report / minimum-farmer thresholds. It takes no
 * parameters, so a caller cannot vary the window, region or thresholds to narrow the data down to individuals.
 * The legacy {@code /api/v1/outbreaks/heatmap} is left as it was.
 */
@RestController
@RequestMapping("/api/folix")
@CrossOrigin(origins = "${cropscanner.cors.allowed-origin:http://localhost:5173}")
public class FolixDiseaseMapController {

    private final FolixDiseaseMapService mapService;

    public FolixDiseaseMapController(FolixDiseaseMapService mapService) {
        this.mapService = mapService;
    }

    @GetMapping("/disease-map")
    public ResponseEntity<FolixDiseaseMapResponse> diseaseMap() {
        // no-store: a farmer who withdraws consent should not remain in a cached copy of the map.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(mapService.currentMap());
    }
}
