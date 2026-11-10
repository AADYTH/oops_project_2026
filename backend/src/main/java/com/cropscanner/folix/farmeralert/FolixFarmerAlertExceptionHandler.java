package com.cropscanner.folix.farmeralert;

import com.cropscanner.folix.FolixReportException;
import com.cropscanner.folix.auth.FolixAuthException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Standard FOLIX error envelope {@code {"error": {code, message, retryable}}} for the farmer alert endpoints.
 */
@RestControllerAdvice(assignableTypes = {FolixFarmerAlertController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FolixFarmerAlertExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(FolixFarmerAlertExceptionHandler.class);

    @ExceptionHandler(FolixFarmerAlertException.class)
    public ResponseEntity<Map<String, Object>> handleAlertException(FolixFarmerAlertException e) {
        return respond(e.code().httpStatus(), e.code().name(), e.getMessage());
    }

    @ExceptionHandler(FolixReportException.class)
    public ResponseEntity<Map<String, Object>> handleReportException(FolixReportException e) {
        if (e.code() == FolixReportException.Code.NOT_FOUND) {
            return respond(404, "NOT_FOUND", "Report not found.");
        }
        return respond(400, "VALIDATION_FAILED", "Invalid report id.");
    }

    @ExceptionHandler(FolixAuthException.class)
    public ResponseEntity<Map<String, Object>> handleAuth(FolixAuthException e) {
        return respond(e.code().httpStatus(), e.code().name(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleBadPathVariable(MethodArgumentTypeMismatchException e) {
        return respond(400, "VALIDATION_FAILED", "Invalid report id.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        log.error("Unexpected failure in FOLIX farmer alerts endpoint", e);
        return respond(500, "SERVER_ERROR", "Something went wrong on our side. Please try again.");
    }

    private static ResponseEntity<Map<String, Object>> respond(int status, String code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        error.put("retryable", status >= 500);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}
