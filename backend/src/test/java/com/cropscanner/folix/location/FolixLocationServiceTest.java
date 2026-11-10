package com.cropscanner.folix.location;

import com.cropscanner.folix.auth.AuthenticatedFarmer;
import com.cropscanner.folix.auth.FolixAuthException;
import com.cropscanner.folix.map.FolixMapSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Stage 6C: {@link FolixLocationService} on its own — consent, coarse storage, withdrawal, fail-closed. */
class FolixLocationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-21T10:00:00Z");
    private static final AuthenticatedFarmer FARMER = new AuthenticatedFarmer(
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), "Asha Nair", "asha@example.com",
            Instant.parse("2026-09-01T00:00:00Z"));

    private FolixFarmerLocationRepository repo;
    private FolixLocationService service;

    @BeforeEach
    void setUp() {
        repo = Mockito.mock(FolixFarmerLocationRepository.class);
        when(repo.save(any(FolixFarmerLocationEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new FolixLocationService(repo, FolixMapSettings.defaults(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void enablingStoresTheCentreOfTheCellNotTheExactPoint() {
        FolixLocationResponse response = service.enable(new FolixLocationRequest(true, 11.2587, 75.7804), FARMER);

        ArgumentCaptor<FolixFarmerLocationEntity> saved = ArgumentCaptor.forClass(FolixFarmerLocationEntity.class);
        verify(repo).save(saved.capture());
        assertEquals(FARMER.id(), saved.getValue().getFarmerId());
        assertEquals(11250, saved.getValue().getRegionLatMdeg());
        assertEquals(75750, saved.getValue().getRegionLngMdeg());
        assertEquals(NOW, saved.getValue().getConsentedAt());

        assertTrue(response.sharingEnabled());
        assertEquals(11.25, response.approximateArea().centerLatitude());
        assertEquals(75.75, response.approximateArea().centerLongitude());
        assertEquals(0.1, response.approximateArea().cellSizeDegrees());
    }

    @Test
    void refreshingUpdatesTheRegionButKeepsTheOriginalConsentTime() {
        FolixFarmerLocationEntity existing = new FolixFarmerLocationEntity(FARMER.id(),
                new FolixRegionCell(11250, 75750), Instant.parse("2026-09-01T00:00:00Z"));
        when(repo.findById(FARMER.id())).thenReturn(Optional.of(existing));

        service.enable(new FolixLocationRequest(true, 13.0827, 80.2707), FARMER);

        assertEquals(13050, existing.getRegionLatMdeg());
        assertEquals(80250, existing.getRegionLngMdeg());
        assertEquals(Instant.parse("2026-09-01T00:00:00Z"), existing.getConsentedAt());
        assertEquals(NOW, existing.getUpdatedAt());
    }

    @Test
    void withoutExplicitConsentNothingIsStored() {
        assertThrows(FolixAuthException.class, () -> service.enable(new FolixLocationRequest(null, 11.0, 75.0), FARMER));
        assertThrows(FolixAuthException.class, () -> service.enable(new FolixLocationRequest(false, 11.0, 75.0), FARMER));
        assertThrows(FolixAuthException.class, () -> service.enable(null, FARMER));
        verify(repo, never()).save(any());
    }

    @Test
    void invalidCoordinatesAreRejectedAndNothingIsStored() {
        assertThrows(FolixAuthException.class, () -> service.enable(new FolixLocationRequest(true, null, 75.0), FARMER));
        assertThrows(FolixAuthException.class, () -> service.enable(new FolixLocationRequest(true, 91.0, 75.0), FARMER));
        assertThrows(FolixAuthException.class,
                () -> service.enable(new FolixLocationRequest(true, 10.0, Double.NaN), FARMER));
        verify(repo, never()).save(any());
    }

    @Test
    void statusIsDisabledWhenThereIsNoRowAndEnabledWhenThereIs() {
        FolixLocationResponse none = service.status(FARMER);
        assertFalse(none.sharingEnabled());
        assertNull(none.approximateArea());

        when(repo.findById(FARMER.id())).thenReturn(Optional.of(
                new FolixFarmerLocationEntity(FARMER.id(), new FolixRegionCell(11250, 75750), NOW)));
        assertTrue(service.status(FARMER).sharingEnabled());
    }

    @Test
    void disablingDeletesTheRegionAndClearsItFromTheFarmersOwnReportsOnly() {
        FolixLocationResponse response = service.disable(FARMER);

        verify(repo).clearRegionFromReportsOf(FARMER.id());
        verify(repo).deleteById(FARMER.id());
        assertFalse(response.sharingEnabled());
    }

    @Test
    void everyOperationWithoutAFarmerFailsClosedAndTouchesNothing() {
        AuthenticatedFarmer noId = new AuthenticatedFarmer(null, "x", "x@example.com", NOW);
        assertThrows(FolixAuthException.class, () -> service.status(null));
        assertThrows(FolixAuthException.class, () -> service.status(noId));
        assertThrows(FolixAuthException.class, () -> service.enable(new FolixLocationRequest(true, 1.0, 1.0), null));
        assertThrows(FolixAuthException.class, () -> service.enable(new FolixLocationRequest(true, 1.0, 1.0), noId));
        assertThrows(FolixAuthException.class, () -> service.disable(null));
        assertThrows(FolixAuthException.class, () -> service.disable(noId));
        verifyNoInteractions(repo);
    }
}
