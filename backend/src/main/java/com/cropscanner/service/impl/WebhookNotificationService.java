package com.cropscanner.service.impl;

import com.cropscanner.model.AuthorityContact;
import com.cropscanner.model.ScanAnalysisResult;
import com.cropscanner.service.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Fires the emergency-escalation alert to a regional authority's registered
 * webhook endpoint using the JDK's built-in {@link HttpClient}, fully
 * asynchronously via {@link HttpClient#sendAsync}.
 *
 * Real-world caveat (see README "Downsides & limitations"): most
 * government agriculture offices do not currently operate a
 * machine-readable webhook receiver. This service still dispatches to any
 * URL configured for an authority (useful for offices that use a ticketing
 * system with an inbound webhook, a Slack/Teams channel, or a custom
 * intake API you stand up yourself), but production deployments will
 * typically need to pair this with an SMS/email fallback for authorities
 * that only have a phone or email listed and no webhook.
 */
@Service
public class WebhookNotificationService implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(WebhookNotificationService.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WebhookNotificationService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public CompletableFuture<Integer> sendSevereScanAlert(ScanAnalysisResult result, AuthorityContact authority) {
        if (!authority.supportsWebhook()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Authority '" + authority.name() + "' has no webhook URL configured"));
        }

        String jsonPayload;
        try {
            jsonPayload = objectMapper.writeValueAsString(buildAlertPayload(result, authority));
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(authority.webhookUrl()))
                .header("Content-Type", "application/json")
                .header("X-Alert-Type", "SEVERE_CROP_DISEASE")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        log.info("Dispatching severe-scan alert for scan {} to authority '{}' at {}",
                result.scanId(), authority.name(), authority.webhookUrl());

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() / 100 != 2) {
                        throw new WebhookRejectedException(
                                "Authority endpoint returned non-2xx status: " + response.statusCode()
                                        + " body=" + response.body());
                    }
                    log.info("Authority '{}' acknowledged alert for scan {} with HTTP {}",
                            authority.name(), result.scanId(), response.statusCode());
                    return response.statusCode();
                })
                .exceptionally(ex -> {
                    log.error("Failed to notify authority '{}' for scan {}: {}",
                            authority.name(), result.scanId(), ex.getMessage());
                    throw new java.util.concurrent.CompletionException(ex);
                });
    }

    private Map<String, Object> buildAlertPayload(ScanAnalysisResult result, AuthorityContact authority) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("alertType", "SEVERE_CROP_DISEASE");
        payload.put("scanId", result.scanId().toString());
        payload.put("detectedAt", result.analyzedAt().toString());
        payload.put("cropName", result.diseaseInfo().cropName());
        payload.put("diseaseName", result.diseaseInfo().diseaseName());
        payload.put("severityPercent", result.diseaseInfo().severityPercent());
        payload.put("isEpidemicRisk", result.diseaseInfo().isEpidemicRisk());
        payload.put("latitude", result.location().latitude());
        payload.put("longitude", result.location().longitude());
        payload.put("notifiedAuthority", authority.name());
        payload.put("notifiedAuthorityDistrict", authority.district());
        return payload;
    }

    /** Thrown when the receiving webhook is reachable but rejects the alert. */
    public static class WebhookRejectedException extends RuntimeException {
        public WebhookRejectedException(String message) {
            super(message);
        }
    }
}
