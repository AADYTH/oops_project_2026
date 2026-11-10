package com.cropscanner.service.impl;

import com.cropscanner.service.SmsGatewayService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Real SMS delivery via Twilio's REST API. Activate with
 * {@code cropscanner.sms.provider=twilio} plus these environment variables:
 *
 *   TWILIO_ACCOUNT_SID   - starts with "AC..."
 *   TWILIO_AUTH_TOKEN    - from the Twilio console
 *   TWILIO_FROM_NUMBER   - a Twilio number you own, E.164 format e.g. +14155551234
 *
 * Deliberately implemented as a plain HTTPS POST (Basic Auth + form body)
 * rather than pulling in the Twilio Java SDK, so no new Maven dependency is
 * needed and this can be reviewed/audited in ~60 lines.
 *
 * NOT wired to any real account by default — without the three env vars
 * above this bean will refuse to send and log a clear warning instead of
 * silently failing, so misconfiguration is loud, not silent.
 */
@Service
@ConditionalOnProperty(name = "cropscanner.sms.provider", havingValue = "twilio")
public class TwilioSmsGatewayService implements SmsGatewayService {

    private static final Logger log = LoggerFactory.getLogger(TwilioSmsGatewayService.class);

    private final String accountSid;
    private final String authToken;
    private final String fromNumber;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public TwilioSmsGatewayService(
            @Value("${TWILIO_ACCOUNT_SID:}") String accountSid,
            @Value("${TWILIO_AUTH_TOKEN:}") String authToken,
            @Value("${TWILIO_FROM_NUMBER:}") String fromNumber) {
        this.accountSid = accountSid;
        this.authToken = authToken;
        this.fromNumber = fromNumber;
        if (accountSid.isBlank() || authToken.isBlank() || fromNumber.isBlank()) {
            log.warn("cropscanner.sms.provider=twilio but TWILIO_ACCOUNT_SID/TWILIO_AUTH_TOKEN/TWILIO_FROM_NUMBER " +
                    "are not fully set. SMS sending will fail until all three are provided as environment variables.");
        }
    }

    @Override
    public boolean sendSms(String toPhoneNumber, String message) {
        if (accountSid.isBlank() || authToken.isBlank() || fromNumber.isBlank()) {
            log.error("Cannot send SMS via Twilio: missing credentials. Message NOT sent to {}.", toPhoneNumber);
            return false;
        }
        if (toPhoneNumber == null || toPhoneNumber.isBlank()) {
            log.warn("Cannot send SMS: no phone number on file for this recipient.");
            return false;
        }

        try {
            String body = "To=" + URLEncoder.encode(toPhoneNumber, StandardCharsets.UTF_8)
                    + "&From=" + URLEncoder.encode(fromNumber, StandardCharsets.UTF_8)
                    + "&Body=" + URLEncoder.encode(message, StandardCharsets.UTF_8);

            String basicAuth = Base64.getEncoder().encodeToString((accountSid + ":" + authToken).getBytes(StandardCharsets.UTF_8));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.twilio.com/2010-04-01/Accounts/" + accountSid + "/Messages.json"))
                    .header("Authorization", "Basic " + basicAuth)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 == 2) {
                log.info("SMS sent via Twilio to {} (HTTP {})", toPhoneNumber, response.statusCode());
                return true;
            }
            log.error("Twilio rejected SMS to {}: HTTP {} — {}", toPhoneNumber, response.statusCode(), response.body());
            return false;
        } catch (Exception e) {
            log.error("Failed to send SMS via Twilio to {}: {}", toPhoneNumber, e.getMessage());
            return false;
        }
    }
}
