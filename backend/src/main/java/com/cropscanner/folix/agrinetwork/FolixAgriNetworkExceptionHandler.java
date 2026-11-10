package com.cropscanner.folix.agrinetwork;

import com.cropscanner.folix.auth.FolixAuthException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The standard FOLIX error envelope {@code {"error": {code, message, retryable, fields?}}} for the
 * Agricultural Network endpoints only. Never echoes framework messages or stack traces. Mirrors
 * {@code FolixLocationExceptionHandler} (Stage 6C).
 */
@RestControllerAdvice(assignableTypes = {FolixAgriNetworkController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FolixAgriNetworkExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(FolixAgriNetworkExceptionHandler.class);

    @ExceptionHandler(FolixAuthException.class)
    public ResponseEntity<Map<String, Object>> handleAuth(FolixAuthException e) {
        return respond(e.code().httpStatus(), e.code().name(), e.getMessage(), e.fields());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(HttpMessageNotReadableException e) {
        return respond(400, FolixAuthException.Code.VALIDATION_FAILED.name(), "Malformed request body.", Map.of());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMediaType(HttpMediaTypeNotSupportedException e) {
        return respond(415, "UNSUPPORTED_MEDIA_TYPE", "Send the request as application/json.", Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        log.error("Unexpected failure in FOLIX agricultural network endpoint", e);
        return respond(500, "SERVER_ERROR", "Something went wrong on our side. Please try again.", Map.of());
    }

    private static ResponseEntity<Map<String, Object>> respond(int status, String code, String message,
                                                               Map<String, String> fields) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        error.put("retryable", status >= 500);
        if (fields != null && !fields.isEmpty()) {
            error.put("fields", fields);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}
