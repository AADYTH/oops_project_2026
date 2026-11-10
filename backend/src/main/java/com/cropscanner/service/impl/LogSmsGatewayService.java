package com.cropscanner.service.impl;

import com.cropscanner.service.SmsGatewayService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Default {@link SmsGatewayService}: logs the message instead of sending it.
 * Active whenever {@code cropscanner.sms.provider} is unset or "log" (the
 * default), so the app is fully functional without an SMS provider account.
 * Swap to {@code twilio} once you have real credentials — see
 * {@link TwilioSmsGatewayService}.
 */
@Service
@ConditionalOnProperty(name = "cropscanner.sms.provider", havingValue = "log", matchIfMissing = true)
public class LogSmsGatewayService implements SmsGatewayService {

    private static final Logger log = LoggerFactory.getLogger(LogSmsGatewayService.class);

    private final boolean enabled;

    public LogSmsGatewayService(@Value("${cropscanner.sms.enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public boolean sendSms(String toPhoneNumber, String message) {
        if (!enabled) {
            log.debug("SMS sending disabled (cropscanner.sms.enabled=false); would have sent to {}: {}",
                    toPhoneNumber, message);
            return false;
        }
        if (toPhoneNumber == null || toPhoneNumber.isBlank()) {
            log.warn("Cannot send SMS: no phone number on file for this recipient.");
            return false;
        }
        // No real SMS provider is configured. This log line is the audit
        // trail for what WOULD have been sent — set
        // cropscanner.sms.provider=twilio with real credentials to actually
        // deliver it.
        log.info("[SMS-NOT-SENT: no provider configured] To: {} | Message: {}", toPhoneNumber, message);
        return true;
    }
}
