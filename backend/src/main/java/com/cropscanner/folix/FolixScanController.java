package com.cropscanner.folix;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * POST /api/scan/analyze — the FOLIX Scanner endpoint.
 *
 * Accepts exactly one multipart part named {@code image}. Nothing else is read:
 * no user identity, no coordinates, no plant-part hint. The response body is the
 * FOLIX diagnosis contract (see {@link DiagnosisResult#toContractMap()}) on
 * success, or {@code {"error": {code, message, retryable}}} on failure (see
 * {@link FolixExceptionHandler}). It is never cached.
 *
 * Deliberately separate from the legacy {@code /api/v1/scans} endpoint, which
 * keeps its own local-model pipeline untouched.
 */
@RestController
@RequestMapping("/api/scan")
@CrossOrigin(origins = "${cropscanner.cors.allowed-origin:http://localhost:5173}")
public class FolixScanController {

    private final FolixScanService scanService;

    public FolixScanController(FolixScanService scanService) {
        this.scanService = scanService;
    }

    @PostMapping("/analyze")
    public ResponseEntity<Map<String, Object>> analyze(
            @RequestParam(value = "image", required = false) MultipartFile image) {

        if (image == null || image.isEmpty()) {
            throw FolixAnalysisException.invalidImage("no 'image' part in the request");
        }
        byte[] bytes;
        try {
            bytes = image.getBytes();
        } catch (IOException e) {
            throw FolixAnalysisException.invalidImage("upload could not be read");
        }

        DiagnosisResult result = scanService.analyze(bytes);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(result.toContractMap());
    }
}
