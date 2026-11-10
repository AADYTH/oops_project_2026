package com.cropscanner.folix;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * /api/scan/reports — connects the Scanner's "Save Report" action, and the
 * Stage 5C farmer Dashboard, to the existing FOLIX report persistence layer.
 *
 * This is intentionally a thin HTTP wrapper: every method below just
 * deserializes/parses the HTTP-level input and hands it to the existing
 * {@link FolixReportService}, which still owns every validation rule and all
 * query logic ({@link FolixReportService#save(FolixReportRequest, AuthenticatedFarmer)},
 * {@link FolixReportService#list(AuthenticatedFarmer, int, int)},
 * {@link FolixReportService#getById(UUID, AuthenticatedFarmer)},
 * {@link FolixReportService#stats(AuthenticatedFarmer)} — only the routes
 * exposing them live here). Nothing here re-implements or duplicates that
 * logic, and the JPA entity ({@link FolixScanReportEntity}) is never read
 * from or written to this boundary — only the existing
 * {@link FolixReportResponse}/{@link FolixReportPage}/{@link FolixReportStats}
 * DTOs cross it.
 *
 * Stage 6A: these routes now require a logged-in farmer. That is enforced by the interceptor
 * registered in {@code com.cropscanner.folix.auth.FolixWebConfig} (401 before this class is reached);
 * {@code allowCredentials} lets the browser send the HttpOnly session cookie cross-origin.
 *
 * Stage 6B: reports belong to the farmer who saved them. Each method takes an
 * {@link AuthenticatedFarmer}, which {@code AuthenticatedFarmerArgumentResolver} builds ONLY from the
 * server-side session (never from a request parameter, header or body — the request body type
 * {@link FolixReportRequest} has no owner field, and any extra {@code farmerId}/{@code owner} property a
 * client adds is ignored). The list, summary and detail endpoints are scoped to that farmer; a report
 * belonging to someone else is answered with the same 404 as a report that does not exist.
 *
 * Deliberately a separate controller from {@link FolixScanController} (which
 * owns /api/scan/analyze) so the "diagnose" and "save/read" paths stay
 * independently testable, matching the separation already used between
 * {@link FolixScanService}/{@link FolixReportService} and
 * {@link FolixAnalysisException}/{@link FolixReportException}. Failures are
 * turned into the same {@code {"error": {code, message, retryable}}} shape
 * as the analyze endpoint by {@link FolixExceptionHandler}.
 */
@RestController
@RequestMapping("/api/scan")
@CrossOrigin(origins = "${cropscanner.cors.allowed-origin:http://localhost:5173}", allowCredentials = "true")
public class FolixReportController {

    private final FolixReportService reportService;

    public FolixReportController(FolixReportService reportService) {
        this.reportService = reportService;
    }

    /**
     * Persists a FOLIX scan report built from the diagnosis the user is
     * currently viewing. The request body must map onto
     * {@link FolixReportRequest} exactly (the same diagnosis contract,
     * minus {@code plant_detected}); {@link FolixReportService} rejects
     * anything malformed or invalid with a {@link FolixReportException}.
     */
    @PostMapping("/reports")
    public ResponseEntity<FolixReportResponse> save(@RequestBody(required = false) FolixReportRequest request,
                                                    AuthenticatedFarmer farmer) {
        FolixReportResponse response = reportService.save(request, farmer);
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    /**
     * Newest-first, paginated list of saved reports for the Dashboard's
     * Recent/Scan History view. {@code page}/{@code size} are passed through
     * untouched to {@link FolixReportService#list(AuthenticatedFarmer, int, int)}, which already
     * clamps them; nothing here invents defaults beyond what that method
     * already does for a missing/zero value.
     */
    @GetMapping("/reports")
    public ResponseEntity<FolixReportPage> list(
            AuthenticatedFarmer farmer,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "0") int size
    ) {
        FolixReportPage response = reportService.list(farmer, page, size);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }

    /**
     * The basic counts ({@link FolixReportStats}) the Dashboard's Overview
     * reads: total saved scans and scans needing human review. Declared
     * ahead of {@link #getById(UUID, AuthenticatedFarmer)} below so the literal path
     * {@code /api/scan/reports/summary} is never mistaken for a report id.
     */
    @GetMapping("/reports/summary")
    public ResponseEntity<FolixReportStats> summary(AuthenticatedFarmer farmer) {
        FolixReportStats response = reportService.stats(farmer);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }

    /**
     * One report's full detail for the Dashboard's Report Detail view.
     * {@link FolixReportService#getById(UUID, AuthenticatedFarmer)} throws
     * {@link FolixReportException#notFound(UUID)} for an unknown id OR an id owned by another farmer, which
     * {@link FolixExceptionHandler} turns into a 404 in the standard FOLIX
     * error envelope.
     */
    @GetMapping("/reports/{id}")
    public ResponseEntity<FolixReportResponse> getById(@PathVariable("id") UUID id, AuthenticatedFarmer farmer) {
        FolixReportResponse response = reportService.getById(id, farmer);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }
}
