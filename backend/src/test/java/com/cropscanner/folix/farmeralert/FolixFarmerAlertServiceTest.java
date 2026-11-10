package com.cropscanner.folix.farmeralert;

import com.cropscanner.folix.FolixReportException;
import com.cropscanner.folix.FolixReportStatus;
import com.cropscanner.folix.FolixScanReportEntity;
import com.cropscanner.folix.FolixScanReportRepository;
import com.cropscanner.folix.auth.AuthenticatedFarmer;
import com.cropscanner.folix.auth.FolixAuthException;
import com.cropscanner.folix.auth.FolixFarmerEntity;
import com.cropscanner.folix.auth.FolixFarmerStatus;
import com.cropscanner.folix.location.FolixFarmerLocationEntity;
import com.cropscanner.folix.location.FolixFarmerLocationRepository;
import com.cropscanner.folix.location.FolixRegionCell;
import com.cropscanner.folix.map.FolixMapSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link FolixFarmerAlertService} (FOLIX Stage 6I).
 */
class FolixFarmerAlertServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-23T10:00:00Z");

    private FolixScanReportRepository reportRepo;
    private FolixFarmerLocationRepository locationRepo;
    private FolixFarmerAlertRepository alertRepo;
    private FolixFarmerAlertService service;

    private AuthenticatedFarmer owner;
    private AuthenticatedFarmer otherFarmer;
    private FolixFarmerEntity ownerEntity;

    private UUID reportId;
    private FolixScanReportEntity epidemicReportWithRegion;
    private FolixScanReportEntity nonEpidemicReport;
    private FolixScanReportEntity epidemicReportWithoutRegion;

    @BeforeEach
    void setUp() {
        reportRepo = Mockito.mock(FolixScanReportRepository.class);
        locationRepo = Mockito.mock(FolixFarmerLocationRepository.class);
        alertRepo = Mockito.mock(FolixFarmerAlertRepository.class);
        service = new FolixFarmerAlertService(reportRepo, locationRepo, alertRepo,
                new FolixMapSettings(100, 3, 3, 30), Clock.fixed(NOW, ZoneOffset.UTC));

        owner = new AuthenticatedFarmer(UUID.randomUUID(), "Alice Farmer", "alice@example.com", NOW);
        otherFarmer = new AuthenticatedFarmer(UUID.randomUUID(), "Bob Farmer", "bob@example.com", NOW);
        ownerEntity = new FolixFarmerEntity(owner.id(), owner.name(), owner.email(), "hash",
                FolixFarmerStatus.ACTIVE, NOW);

        reportId = UUID.randomUUID();

        // 1. Epidemic report with region
        epidemicReportWithRegion = new FolixScanReportEntity(
                reportId, ownerEntity, NOW, "Tomato", "Leaf", "Late blight", "Severe",
                "High", List.of("lesions"), "reasoning", List.of("action"), false,
                null, FolixReportStatus.RECEIVED, true);
        epidemicReportWithRegion.assignRegion(new FolixRegionCell(12000, 77000));

        // 2. Non-epidemic report
        nonEpidemicReport = new FolixScanReportEntity(
                UUID.randomUUID(), ownerEntity, NOW, "Tomato", "Leaf", "Nutrient deficiency", "Low",
                "High", List.of("yellowing"), "reasoning", List.of("action"), false,
                null, FolixReportStatus.RECEIVED, false);
        nonEpidemicReport.assignRegion(new FolixRegionCell(12000, 77000));

        // 3. Epidemic report without region (farmer did not opt in)
        epidemicReportWithoutRegion = new FolixScanReportEntity(
                UUID.randomUUID(), ownerEntity, NOW, "Tomato", "Leaf", "Late blight", "Severe",
                "High", List.of("lesions"), "reasoning", List.of("action"), false,
                null, FolixReportStatus.RECEIVED, true);

        when(reportRepo.findByIdAndFarmer_Id(eq(reportId), eq(owner.id())))
                .thenReturn(Optional.of(epidemicReportWithRegion));
        when(reportRepo.findByIdAndFarmer_Id(eq(nonEpidemicReport.getId()), eq(owner.id())))
                .thenReturn(Optional.of(nonEpidemicReport));
        when(reportRepo.findByIdAndFarmer_Id(eq(epidemicReportWithoutRegion.getId()), eq(owner.id())))
                .thenReturn(Optional.of(epidemicReportWithoutRegion));
    }

    @Test
    void triggerAlertsFailsClosedWhenUnauthenticated() {
        assertThrows(FolixAuthException.class, () -> service.triggerAlerts(reportId, null));
        assertThrows(FolixAuthException.class, () -> service.triggerAlerts(reportId,
                new AuthenticatedFarmer(null, "anon", "anon@example.com", NOW)));
        verify(alertRepo, never()).saveAll(any());
    }

    @Test
    void triggerAlertsRejectsNullReportId() {
        assertThrows(FolixReportException.class, () -> service.triggerAlerts(null, owner));
    }

    @Test
    void triggerAlertsRejectsUnownedOrNonExistentReport() {
        UUID unknownId = UUID.randomUUID();
        when(reportRepo.findByIdAndFarmer_Id(eq(unknownId), eq(owner.id()))).thenReturn(Optional.empty());

        FolixReportException ex = assertThrows(FolixReportException.class,
                () -> service.triggerAlerts(unknownId, owner));
        assertEquals(FolixReportException.Code.NOT_FOUND, ex.code());

        // Someone else's report is also 404
        assertThrows(FolixReportException.class,
                () -> service.triggerAlerts(reportId, otherFarmer));
    }

    @Test
    void triggerAlertsRejectsNonEpidemicRiskReport() {
        FolixFarmerAlertException ex = assertThrows(FolixFarmerAlertException.class,
                () -> service.triggerAlerts(nonEpidemicReport.getId(), owner));
        assertEquals(FolixFarmerAlertException.Code.NOT_EPIDEMIC_RISK, ex.code());
        verify(locationRepo, never()).findNearbyExcluding(anyInt(), anyInt(), anyInt(), anyInt(), any());
        verify(alertRepo, never()).saveAll(any());
    }

    @Test
    void triggerAlertsRejectsReportWithoutRegion() {
        FolixFarmerAlertException ex = assertThrows(FolixFarmerAlertException.class,
                () -> service.triggerAlerts(epidemicReportWithoutRegion.getId(), owner));
        assertEquals(FolixFarmerAlertException.Code.NO_LOCATION, ex.code());
        verify(locationRepo, never()).findNearbyExcluding(anyInt(), anyInt(), anyInt(), anyInt(), any());
        verify(alertRepo, never()).saveAll(any());
    }

    @Test
    void triggerAlertsFindsNearbyFarmersUsing3x3NeighbourhoodExcludingSender() {
        UUID neighbor1 = UUID.randomUUID();
        UUID neighbor2 = UUID.randomUUID();

        // 3x3 neighbourhood for cell (12000, 77000) with cellSize 100:
        // lat in [11900, 12100], lng in [76900, 77100]
        when(locationRepo.findNearbyExcluding(11900, 12100, 76900, 77100, owner.id()))
                .thenReturn(List.of(
                        new FolixFarmerLocationEntity(neighbor1, new FolixRegionCell(12000, 77000), NOW),
                        new FolixFarmerLocationEntity(neighbor2, new FolixRegionCell(12100, 77100), NOW)
                ));

        Instant cooldownCutoff = NOW.minus(Duration.ofHours(24));
        when(alertRepo.existsByRecipientFarmerIdAndDiseaseNameAndCreatedAtAfter(
                eq(neighbor1), eq("Late blight"), eq(cooldownCutoff))).thenReturn(false);
        when(alertRepo.existsByRecipientFarmerIdAndDiseaseNameAndCreatedAtAfter(
                eq(neighbor2), eq("Late blight"), eq(cooldownCutoff))).thenReturn(false);

        FolixFarmerAlertTriggerResponse response = service.triggerAlerts(reportId, owner);

        assertEquals(reportId, response.reportId());
        assertEquals(2, response.alertsSent());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FolixFarmerAlertEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(alertRepo).saveAll(captor.capture());

        List<FolixFarmerAlertEntity> saved = captor.getValue();
        assertEquals(2, saved.size());

        // Verify recipient 1
        FolixFarmerAlertEntity a1 = saved.get(0);
        assertEquals(neighbor1, a1.getRecipientFarmerId());
        assertEquals("Late blight", a1.getDiseaseName());
        assertEquals("Tomato", a1.getCropName());
        assertEquals(12000, a1.getRegionLatMdeg());
        assertEquals(77000, a1.getRegionLngMdeg());
        assertTrue(a1.isEpidemicRisk());
        assertEquals(NOW, a1.getCreatedAt());
        assertEquals(NOW.plus(Duration.ofDays(7)), a1.getExpiresAt());
    }

    @Test
    void triggerAlertsEnforces24HourCooldownPerRecipientAndDisease() {
        UUID neighborOnCooldown = UUID.randomUUID();
        UUID freshNeighbor = UUID.randomUUID();

        when(locationRepo.findNearbyExcluding(11900, 12100, 76900, 77100, owner.id()))
                .thenReturn(List.of(
                        new FolixFarmerLocationEntity(neighborOnCooldown, new FolixRegionCell(12000, 77000), NOW),
                        new FolixFarmerLocationEntity(freshNeighbor, new FolixRegionCell(12000, 77000), NOW)
                ));

        Instant cooldownCutoff = NOW.minus(Duration.ofHours(24));
        // neighborOnCooldown received Late blight within 24h
        when(alertRepo.existsByRecipientFarmerIdAndDiseaseNameAndCreatedAtAfter(
                eq(neighborOnCooldown), eq("Late blight"), eq(cooldownCutoff))).thenReturn(true);
        // freshNeighbor has not
        when(alertRepo.existsByRecipientFarmerIdAndDiseaseNameAndCreatedAtAfter(
                eq(freshNeighbor), eq("Late blight"), eq(cooldownCutoff))).thenReturn(false);

        FolixFarmerAlertTriggerResponse response = service.triggerAlerts(reportId, owner);

        assertEquals(1, response.alertsSent());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FolixFarmerAlertEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(alertRepo).saveAll(captor.capture());

        List<FolixFarmerAlertEntity> saved = captor.getValue();
        assertEquals(1, saved.size());
        assertEquals(freshNeighbor, saved.get(0).getRecipientFarmerId());
    }

    @Test
    void getMyAlertsReturnsActiveAlertsWithoutExposingSenderOrExactLocation() {
        FolixFarmerAlertEntity alert = new FolixFarmerAlertEntity(
                owner.id(), "Late blight", "Tomato", 12000, 77000, true,
                NOW.minus(Duration.ofHours(2)), NOW.plus(Duration.ofDays(6)));

        when(alertRepo.findByRecipientFarmerIdAndExpiresAtAfterOrderByCreatedAtDesc(eq(owner.id()), eq(NOW)))
                .thenReturn(List.of(alert));

        List<FolixFarmerAlertResponse> responses = service.getMyAlerts(owner);

        assertEquals(1, responses.size());
        FolixFarmerAlertResponse res = responses.get(0);
        assertEquals("Late blight", res.diseaseName());
        assertEquals("Tomato", res.cropName());
        assertEquals("Nearby area", res.regionDescription());
        assertTrue(res.epidemicRisk());
        assertEquals(alert.getCreatedAt(), res.createdAt());
        assertEquals(alert.getExpiresAt(), res.expiresAt());
    }

    @Test
    void getMyAlertsFiltersOutAlertsFromDistantLocationsWhenFarmerLocationIsKnown() {
        // Farmer is located at (12000, 77000)
        when(locationRepo.findById(eq(owner.id()))).thenReturn(Optional.of(
                new FolixFarmerLocationEntity(owner.id(), new FolixRegionCell(12000, 77000), NOW)
        ));

        // Alert 1: nearby cell (12050, 77050)
        FolixFarmerAlertEntity nearbyAlert = new FolixFarmerAlertEntity(
                owner.id(), "Late blight", "Tomato", 12050, 77050, true,
                NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofDays(6)));

        // Alert 2: distant cell in Delhi/Punjab (28000, 77000)
        FolixFarmerAlertEntity distantAlert = new FolixFarmerAlertEntity(
                owner.id(), "Rice blast", "Rice", 28000, 77000, true,
                NOW.minus(Duration.ofHours(2)), NOW.plus(Duration.ofDays(6)));

        when(alertRepo.findByRecipientFarmerIdAndExpiresAtAfterOrderByCreatedAtDesc(eq(owner.id()), eq(NOW)))
                .thenReturn(List.of(nearbyAlert, distantAlert));

        List<FolixFarmerAlertResponse> responses = service.getMyAlerts(owner);

        assertEquals(1, responses.size());
        assertEquals("Late blight", responses.get(0).diseaseName());
    }

    @Test
    void getMyAlertsFailsClosedWhenUnauthenticated() {
        assertThrows(FolixAuthException.class, () -> service.getMyAlerts(null));
        assertThrows(FolixAuthException.class, () -> service.getMyAlerts(
                new AuthenticatedFarmer(null, "anon", "anon@example.com", NOW)));
    }
}
