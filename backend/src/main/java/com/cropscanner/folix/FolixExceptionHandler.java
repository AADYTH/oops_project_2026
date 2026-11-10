package com.cropscanner.folix;

import com.cropscanner.folix.auth.FolixAuthException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns every Scanner failure into the clean error structure
 *
 *   { "error": { "code": "...", "message": "...", "retryable": true|false } }
 *
 * Scoped to {@link FolixScanController} and {@link FolixReportController} only,
 * so the legacy endpoints keep the legacy {@code GlobalExceptionHandler}
 * behaviour. Nothing here ever includes a stack trace, an exception message,
 * an API key, or a Gemini request/response.
 */
@RestControllerAdvice(assignableTypes = {FolixScanController.class, FolixReportController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FolixExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(FolixExceptionHandler.class);

    @ExceptionHandler(FolixAnalysisException.class)
    public ResponseEntity<Map<String, Object>> handleAnalysis(FolixAnalysisException e) {
        // Operational detail goes to the server log only (it is written to be free of secrets and content).
        log.warn("FOLIX analysis failed: {} - {}", e.code(), e.getMessage());
        return respond(e);
    }

    @ExceptionHandler(FolixReportException.class)
    public ResponseEntity<Map<String, Object>> handleReport(FolixReportException e) {
        log.warn("FOLIX report request rejected: {} - {}", e.code(), e.getMessage());
        boolean notFound = e.code() == FolixReportException.Code.NOT_FOUND;
        return respondReport(notFound ? 404 : 400, e.code().name(), e.getMessage());
    }

    /**
     * Stage 6B defence in depth. The auth interceptor already answers 401 before a report route is reached;
     * this only matters if a report method is somehow invoked without a farmer, in which case it must fail
     * closed as 401 (never fall through to the 500 handler below, and never behave as "anonymous").
     */
    @ExceptionHandler(FolixAuthException.class)
    public ResponseEntity<Map<String, Object>> handleUnauthenticated(FolixAuthException e) {
        return respondReport(e.code().httpStatus(), e.code().name(), e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(HttpMessageNotReadableException e) {
        // e.g. a malformed/missing JSON body on POST /api/scan/reports; never a diagnosis, never saved.
        return respondReport(400, "VALIDATION_FAILED", "malformed report request body");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleBadPathVariable(MethodArgumentTypeMismatchException e) {
        // e.g. GET /api/scan/reports/{id} with an id that isn't a valid UUID — a client/Dashboard
        // bug, not a server failure, so this stays a 400 rather than falling through to 500 below.
        return respondReport(400, "VALIDATION_FAILED", "invalid " + e.getName());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleTooLarge(MaxUploadSizeExceededException e) {
        return respond(FolixAnalysisException.tooLarge("multipart size limit exceeded"));
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Map<String, Object>> handleMultipart(MultipartException e) {
        return respond(FolixAnalysisException.invalidImage("malformed multipart request"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        log.error("Unexpected failure in FOLIX Scanner", e);
        return respond(FolixAnalysisException.failed("unexpected server error", true));
    }

    /** Same {@code {"error": {code, message, retryable}}} envelope as {@link #respond}, for FOLIX report failures. */
    private static ResponseEntity<Map<String, Object>> respondReport(int httpStatus, String code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        error.put("retryable", false);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        return ResponseEntity.status(httpStatus).cacheControl(CacheControl.noStore()).body(body);
    }

    static ResponseEntity<Map<String, Object>> respond(FolixAnalysisException e) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", e.code().name());
        error.put("message", e.clientMessage());
        error.put("retryable", e.retryable());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(e.code().httpStatus())
                .cacheControl(CacheControl.noStore());
        if (e.retryAfterSeconds() != null) {
            builder.header("Retry-After", String.valueOf(e.retryAfterSeconds()));
        }
        return builder.body(body);
    }
}
