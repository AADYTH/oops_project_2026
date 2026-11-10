package com.cropscanner.controller;

import com.cropscanner.dto.ScanResponse;
import com.cropscanner.model.Location;
import com.cropscanner.model.ScanAnalysisResult;
import com.cropscanner.service.CropScannerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1/scans")
@Validated
@CrossOrigin(origins = "${cropscanner.cors.allowed-origin:http://localhost:5173}")
public class ScanController {

    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024; // 10 MB

    private final CropScannerService cropScannerService;

    public ScanController(CropScannerService cropScannerService) {
        this.cropScannerService = cropScannerService;
    }

    /**
     * Submits a plant-part photo for diagnosis. GPS coordinates are optional
     * so the endpoint still works for users who deny location permission —
     * escalation for a severe result will then be recorded as
     * "no authority found: no location provided" rather than the scan
     * itself failing.
     */
    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<ScanResponse> submitScan(
            @RequestParam("image") @NotNull MultipartFile image,
            @RequestParam(value = "latitude", required = false) Double latitude,
            @RequestParam(value = "longitude", required = false) Double longitude,
            @RequestParam(value = "plantPart", defaultValue = "UNKNOWN") String plantPart,
            @RequestParam(value = "skipQualityCheck", defaultValue = "false") boolean skipQualityCheck
    ) throws IOException {

        if (image.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        if (image.getSize() > MAX_IMAGE_BYTES) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        }
        String contentType = image.getContentType();
        if (contentType == null || !(contentType.equals("image/jpeg") || contentType.equals("image/png") || contentType.equals("image/webp"))) {
            return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).build();
        }

        Location location = (latitude != null && longitude != null) ? new Location(latitude, longitude) : null;

        ScanAnalysisResult result = cropScannerService.analyzeScan(image.getBytes(), plantPart, location, skipQualityCheck);

        return ResponseEntity.ok(ScanResponse.from(result));
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }
}
