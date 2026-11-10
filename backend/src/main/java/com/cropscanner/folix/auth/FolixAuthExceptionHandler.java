package com.cropscanner.folix.auth;

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
 * Turns account-system failures into the standard FOLIX envelope
 * {@code {"error": {code, message, retryable, fields?}}}. Scoped to the two account controllers only,
 * so the Scanner, the report endpoints and the legacy API keep their own handlers. Nothing here
 * includes a stack trace, an exception message from the framework, a password, a hash or a token.
 */
@RestControllerAdvice(assignableTypes = {FolixAuthController.class, FolixFarmerController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FolixAuthExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(FolixAuthExceptionHandler.class);

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
        log.error("Unexpected failure in FOLIX account endpoint", e);
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
