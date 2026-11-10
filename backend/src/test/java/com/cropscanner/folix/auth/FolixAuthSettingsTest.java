package com.cropscanner.folix.auth;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Misconfiguration must fail at startup, not silently produce a cookie the browser drops. */
class FolixAuthSettingsTest {

    @Test
    void sameSiteNoneWithoutSecureIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new FolixAuthSettings(Duration.ofHours(1), "FOLIX_SESSION", false, "None", Set.of()));
    }

    @Test
    void sameSiteNoneWithSecureIsAcceptedAndCanonicalized() {
        FolixAuthSettings s = new FolixAuthSettings(Duration.ofHours(1), "FOLIX_SESSION", true, "none", Set.of());
        assertEquals("None", s.cookieSameSite());
    }

    @Test
    void unknownSameSiteValueIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new FolixAuthSettings(Duration.ofHours(1), "FOLIX_SESSION", true, "Sometimes", Set.of()));
    }

    @Test
    void nonPositiveLifetimeAndBlankCookieNameAreRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new FolixAuthSettings(Duration.ZERO, "FOLIX_SESSION", false, "Lax", Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new FolixAuthSettings(Duration.ofHours(1), " ", false, "Lax", Set.of()));
    }

    @Test
    void laxWithoutSecureIsTheValidLocalDevelopmentSetup() {
        assertDoesNotThrow(() -> new FolixAuthSettings(Duration.ofHours(168), "FOLIX_SESSION", false, "Lax", Set.of()));
    }

    @Test
    void originsAreNormalizedForComparison() {
        assertEquals("http://localhost:5173", FolixAuthSettings.normalizeOrigin(" HTTP://Localhost:5173/ "));
    }
}
