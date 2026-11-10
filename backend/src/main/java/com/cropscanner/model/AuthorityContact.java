package com.cropscanner.model;

/**
 * A regional agricultural authority (Krishi Vigyan Kendra, District
 * Agriculture Extension Office, or equivalent) that can be alerted when a
 * severe / epidemic-risk scan is detected near it.
 *
 * @param id             internal identifier
 * @param name           official name of the office
 * @param district       administrative district it serves
 * @param state          state/province it serves
 * @param location       geocoded coordinates of the office
 * @param webhookUrl     HTTPS endpoint this office has registered to receive automated alerts, if any
 * @param phoneNumber    human-staffed contact line, used as fallback / included in every alert
 * @param email          contact email, used as fallback / included in every alert
 * @param distanceKm     distance from the scan location; populated by the repository lookup, not stored
 */
public record AuthorityContact(
        Long id,
        String name,
        String district,
        String state,
        Location location,
        String webhookUrl,
        String phoneNumber,
        String email,
        Double distanceKm
) {
    /** True if this contact can receive an automated machine-to-machine webhook alert. */
    public boolean supportsWebhook() {
        return webhookUrl != null && !webhookUrl.isBlank();
    }
}
