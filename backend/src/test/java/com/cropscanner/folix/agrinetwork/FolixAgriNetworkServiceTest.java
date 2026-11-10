package com.cropscanner.folix.agrinetwork;

import com.cropscanner.folix.auth.AuthenticatedFarmer;
import com.cropscanner.folix.auth.FolixAuthException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Stage 6D: {@link FolixAgriNetworkService} on its own — profile save/load and the support directory. */
class FolixAgriNetworkServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-21T10:00:00Z");
    private static final AuthenticatedFarmer FARMER = new AuthenticatedFarmer(
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), "Asha Nair", "asha@example.com",
            Instant.parse("2026-09-01T00:00:00Z"));

    private FolixFarmerAgriProfileRepository profileRepo;
    private FolixSupportContactRepository contactRepo;
    private FolixAgriNetworkService service;

    @BeforeEach
    void setUp() {
        profileRepo = Mockito.mock(FolixFarmerAgriProfileRepository.class);
        contactRepo = Mockito.mock(FolixSupportContactRepository.class);
        when(profileRepo.save(any(FolixFarmerAgriProfileEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new FolixAgriNetworkService(profileRepo, contactRepo, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ---------------------------------------------------------------- profile

    @Test
    void aFarmerWithNoSavedProfileGetsAnEmptyOneNotAnError() {
        when(profileRepo.findById(FARMER.id())).thenReturn(Optional.empty());
        FolixAgriProfileResponse response = service.getProfile(FARMER);
        assertFalse(response.hasProfile());
        assertNull(response.villageOrArea());
        assertNull(response.crops());
    }

    @Test
    void savingCreatesANewProfileForTheAuthenticatedFarmer() {
        when(profileRepo.findById(FARMER.id())).thenReturn(Optional.empty());
        FolixAgriProfileResponse response = service.saveProfile(
                new FolixAgriProfileRequest("  Kaipamangalam  ", " Rice, banana ",
                        FolixSupportCategory.CROP_DISEASE_ASSISTANCE), FARMER);

        assertTrue(response.hasProfile());
        assertEquals("Kaipamangalam", response.villageOrArea());
        assertEquals("Rice, banana", response.crops());
        assertEquals(FolixSupportCategory.CROP_DISEASE_ASSISTANCE, response.preferredCategory());
        assertEquals(NOW, response.updatedAt());
    }

    @Test
    void blankFieldsAreStoredAsNullNotEmptyStrings() {
        when(profileRepo.findById(FARMER.id())).thenReturn(Optional.empty());
        FolixAgriProfileResponse response = service.saveProfile(
                new FolixAgriProfileRequest("   ", "", null), FARMER);
        assertNull(response.villageOrArea());
        assertNull(response.crops());
    }

    @Test
    void savingUpdatesTheExistingRowRatherThanCreatingASecondOne() {
        FolixFarmerAgriProfileEntity existing = new FolixFarmerAgriProfileEntity(FARMER.id(), "Old village",
                "Old crop", FolixSupportCategory.GENERAL_SUPPORT, Instant.parse("2026-09-01T00:00:00Z"));
        when(profileRepo.findById(FARMER.id())).thenReturn(Optional.of(existing));

        service.saveProfile(new FolixAgriProfileRequest("New village", "New crop",
                FolixSupportCategory.AGRICULTURAL_OFFICER), FARMER);

        assertEquals("New village", existing.getVillageOrArea());
        assertEquals("New crop", existing.getCrops());
        assertEquals(FolixSupportCategory.AGRICULTURAL_OFFICER, existing.getPreferredCategory());
        assertEquals(NOW, existing.getUpdatedAt());
    }

    @Test
    void overlyLongFieldsAreRejected() {
        String tooLong = "x".repeat(151);
        assertThrows(FolixAuthException.class,
                () -> service.saveProfile(new FolixAgriProfileRequest(tooLong, null, null), FARMER));
    }

    @Test
    void aNullRequestIsRejected() {
        assertThrows(FolixAuthException.class, () -> service.saveProfile(null, FARMER));
    }

    @Test
    void aNullOrIdlessFarmerFailsClosedForEveryProfileMethod() {
        assertThrows(FolixAuthException.class, () -> service.getProfile(null));
        assertThrows(FolixAuthException.class, () -> service.saveProfile(new FolixAgriProfileRequest("a", "b", null), null));
        assertThrows(FolixAuthException.class,
                () -> service.getProfile(new AuthenticatedFarmer(null, "x", "x@example.com", NOW)));
    }

    // ---------------------------------------------------------------- directory

    @Test
    void directoryReturnsOnlyActiveContactsMappedToTheNarrowResponse() {
        FolixSupportContactEntity active = new FolixSupportContactEntity("Demo Officer",
                FolixSupportCategory.AGRICULTURAL_OFFICER, "FOLIX Demo Directory", "Sample locality",
                null, null, null, true, true);
        when(contactRepo.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(active));

        List<FolixSupportContactResponse> directory = service.directory(FARMER);

        assertEquals(1, directory.size());
        assertEquals("Demo Officer", directory.get(0).name());
        assertNull(directory.get(0).phone());
        assertNull(directory.get(0).email());
        assertNull(directory.get(0).address());
        // The repository query itself is what excludes inactive rows; confirm the service asked for that query.
        verify(contactRepo).findByActiveTrueOrderByNameAsc();
    }

    @Test
    void anEmptyDirectoryIsAnEmptyListNotAnError() {
        when(contactRepo.findByActiveTrueOrderByNameAsc()).thenReturn(List.of());
        assertTrue(service.directory(FARMER).isEmpty());
    }

    @Test
    void directoryRequiresAnAuthenticatedFarmerToo() {
        assertThrows(FolixAuthException.class, () -> service.directory(null));
    }
}
