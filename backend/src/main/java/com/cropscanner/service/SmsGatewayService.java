package com.cropscanner.service;

/**
 * Sends a plain-text SMS. This is the fallback channel used when a severe
 * scan needs to reach an authority (or, in future, a farmer) that has no
 * machine-readable webhook — see README "Downsides & limitations": most
 * government agriculture offices only have a phone line, not a webhook
 * receiver.
 *
 * Two implementations ship with this project:
 *  - {@code LogSmsGatewayService} (default, always active): writes the
 *    message to the application log instead of sending it. This keeps the
 *    system 100% functional out of the box without requiring an SMS
 *    provider account, and makes every alert auditable in logs even before
 *    a real provider is wired in.
 *  - {@code TwilioSmsGatewayService} (opt-in via
 *    {@code cropscanner.sms.provider=twilio}): sends a real SMS through
 *    Twilio's REST API once {@code TWILIO_ACCOUNT_SID}, {@code TWILIO_AUTH_TOKEN}
 *    and {@code TWILIO_FROM_NUMBER} are supplied as environment variables.
 *    No Twilio SDK dependency is required — it's a plain HTTPS POST — so
 *    this works without adding a new Maven dependency.
 *
 * For USSD (feature-phone) reach, pair this with a gateway such as Africa's
 * Talking or a local telco's USSD aggregator; the payload shape needed is
 * the same plain-text message, just delivered over a different transport.
 */
public interface SmsGatewayService {

    /**
     * @return true if the message was accepted for delivery (or, for the
     * log-only implementation, successfully logged); false if the number
     * was invalid/missing or the provider rejected it.
     */
    boolean sendSms(String toPhoneNumber, String message);
}
