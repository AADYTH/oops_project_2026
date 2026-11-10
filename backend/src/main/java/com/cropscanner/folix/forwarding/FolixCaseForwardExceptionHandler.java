package com.cropscanner.folix.forwarding;

import com.cropscanner.folix.FolixReportException;
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
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The standard FOLIX error envelope {@code {"error": {code, message, retryable}}} for the case-forwarding
 * endpoints only. Never echoes framework messages or stack traces. A report that is missing or owned by
 * someone else is the same 404.
 */
@RestControllerAdvice(assignableTypes = {FolixCaseForwardController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FolixCaseForwardExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(FolixCaseForwardExceptionHandler.class);

    @ExceptionHandler(FolixCaseForwardException.class)
    public ResponseEntity<Map<String, Object>> handleForward(FolixCaseForwardException e) {
        return respond(e.code().httpStatus(), e.code().name(), e.getMessage());
    }

    @ExceptionHandler(FolixReportException.class)
    public ResponseEntity<Map<String, Object>> handleReport(FolixReportException e) {
        if (e.code() == FolixReportException.Code.NOT_FOUND) {
            return respond(404, "NOT_FOUND", "Report not found.");
        }
        return respond(400, "VALIDATION_FAILED", "Invalid report id.");
    }

    @ExceptionHandler(FolixAuthException.class)
    public ResponseEntity<Map<String, Object>> handleAuth(FolixAuthException e) {
        return respond(e.code().httpStatus(), e.code().name(), e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(HttpMessageNotReadableException e) {
        return respond(400, "VALIDATION_FAILED", "Malformed request body.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleBadPathVariable(MethodArgumentTypeMismatchException e) {
        return respond(400, "VALIDATION_FAILED", "Invalid report id.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMediaType(HttpMediaTypeNotSupportedException e) {
        return respond(415, "UNSUPPORTED_MEDIA_TYPE", "Send the request as application/json.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        log.error("Unexpected failure in FOLIX case forwarding endpoint", e);
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
