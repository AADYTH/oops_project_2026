package com.cropscanner.exception;

import com.cropscanner.service.InferenceService;
import com.cropscanner.service.impl.NoLeafDetectedException;
import com.cropscanner.service.impl.RemoteMlInferenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InferenceService.InferenceUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleInferenceUnavailable(InferenceService.InferenceUnavailableException e) {
        log.error("Inference backend unavailable", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(errorBody(
                "DIAGNOSIS_UNAVAILABLE",
                "The disease-diagnosis model is temporarily unavailable. Please try again shortly. " +
                        "No diagnosis was fabricated for this image."));
    }

    @ExceptionHandler(NoLeafDetectedException.class)
    public ResponseEntity<Map<String, Object>> handleNoLeafDetected(NoLeafDetectedException e) {
        log.info("Scan rejected: no leaf detected (plantMaterialRatio={}, largestRegionRatio={})",
                e.plantMaterialRatio(), e.largestRegionRatio());
        Map<String, Object> body = errorBody("NO_LEAF_DETECTED", e.getMessage());
        body.put("plantMaterialRatio", e.plantMaterialRatio());
        body.put("largestRegionRatio", e.largestRegionRatio());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    @ExceptionHandler(RemoteMlInferenceService.ImageQualityException.class)
    public ResponseEntity<Map<String, Object>> handleImageQuality(RemoteMlInferenceService.ImageQualityException e) {
        log.warn("Scan rejected on image quality: {} (blurScore={}, brightness={})",
                e.errorCode(), e.blurScore(), e.brightness());
        Map<String, Object> body = errorBody(e.errorCode(), e.getMessage());
        body.put("blurScore", e.blurScore());
        body.put("brightness", e.brightness());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadInput(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(errorBody("INVALID_INPUT", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        return ResponseEntity.badRequest().body(errorBody("VALIDATION_ERROR", message));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleTooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(errorBody("IMAGE_TOO_LARGE", "Uploaded image exceeds the maximum allowed size."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorBody("INTERNAL_ERROR", "An unexpected error occurred."));
    }

    private Map<String, Object> errorBody(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("errorCode", code);
        body.put("message", message);
        return body;
    }
}
