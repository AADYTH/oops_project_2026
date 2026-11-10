package com.cropscanner.folix.forwarding;

import com.cropscanner.folix.FolixReportException;
import com.cropscanner.folix.FolixReportStatus;
import com.cropscanner.folix.FolixScanReportEntity;
import com.cropscanner.folix.FolixScanReportRepository;
import com.cropscanner.folix.agrinetwork.FolixSupportCategory;
import com.cropscanner.folix.agrinetwork.FolixSupportContactEntity;
import com.cropscanner.folix.agrinetwork.FolixSupportContactRepository;
import com.cropscanner.folix.auth.AuthenticatedFarmer;
import com.cropscanner.folix.auth.FolixAuthException;
import com.cropscanner.folix.auth.FolixFarmerEntity;
import com.cropscanner.folix.auth.FolixFarmerStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stage 6E Part 1 — the case-forwarding rules, with Mockito stand-ins for the three repositories (same
 * convention as the other FOLIX service tests). Ownership is enforced by the owner-scoped repository
 * lookup, so the stand-in returns the report only for its owner's id.
 */
class FolixCaseForwardServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-21T10:00:00Z");

    private FolixScanReportRepository reportRepo;
    private FolixSupportContactRepository contactRepo;
    private FolixCaseForwardRepository forwardRepo;
    private FolixCaseForwardService service;

    private AuthenticatedFarmer owner;
    private AuthenticatedFarmer other;
    private FolixScanReportEntity report;
    private FolixSupportContactEntity activeContact;
    private FolixSupportContactEntity inactiveContact;

    @BeforeEach
    void setUp() throws Exception {
        reportRepo = Mockito.mock(FolixScanReportRepository.class);
        contactRepo = Mockito.mock(FolixSupportContactRepository.class);
        forwardRepo = Mockito.mock(FolixCaseForwardRepository.class);
        service = new FolixCaseForwardService(reportRepo, contactRepo, forwardRepo,
                Clock.fixed(NOW, ZoneOffset.UTC));

        owner = new AuthenticatedFarmer(UUID.randomUUID(), "Asha Nair", "asha@example.com", NOW);
        other = new AuthenticatedFarmer(UUID.randomUUID(), "Bala Menon", "bala@example.com", NOW);

        FolixFarmerEntity ownerEntity = new FolixFarmerEntity(owner.id(), owner.name(), owner.email(), "hash",
                FolixFarmerStatus.ACTIVE, NOW);
        report = new FolixScanReportEntity(UUID.randomUUID(), ownerEntity, NOW, "Rice", "Leaf", "Blast", "Moderate",
                "High", List.of("spots"), "reasoning", List.of("act"), false, FolixReportStatus.RECEIVED);

        activeContact = contact(1L, true);
        inactiveContact = contact(2L, false);

        when(reportRepo.findByIdAndFarmer_Id(any(UUID.class), any(UUID.class))).thenAnswer(inv ->
                report.getId().equals(inv.getArgument(0)) && owner.id().equals(inv.getArgument(1))
                        ? Optional.of(report) : Optional.empty());
        when(contactRepo.findById(1L)).thenReturn(Optional.of(activeContact));
        when(contactRepo.findById(2L)).thenReturn(Optional.of(inactiveContact));
        when(contactRepo.findById(99L)).thenReturn(Optional.empty());
        when(forwardRepo.findByReport_IdAndReport_Farmer_Id(any(UUID.class), any(UUID.class)))
                .thenReturn(Optional.empty());
        when(forwardRepo.saveAndFlush(any(FolixCaseForwardEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static FolixSupportContactEntity contact(long id, boolean active) throws Exception {
        FolixSupportContactEntity c = new FolixSupportContactEntity("Demo Officer " + id,
                FolixSupportCategory.AGRICULTURAL_OFFICER, "Demo Office", "Demo District", null, null, null,
                active, true);
        Field f = FolixSupportContactEntity.class.getDeclaredField("id");
        f.setAccessible(true);
        f.set(c, id);
        return c;
    }

    @Test
    void ownerForwardsTheirOwnReportToAnActiveContact() {
        FolixCaseForwardResponse response = service.forward(report.getId(), new FolixCaseForwardRequest(1L), owner);

        assertEquals(report.getId(), response.reportId());
        assertEquals("FORWARDED", response.status());
        assertEquals(NOW, response.forwardedAt());
        assertEquals(1L, response.supportContact().id());
        verify(forwardRepo).saveAndFlush(any(FolixCaseForwardEntity.class));
    }

    @Test
    void anotherFarmersReportIsNotFoundAndNothingIsSaved() {
        assertThrows(FolixReportException.class,
                () -> service.forward(report.getId(), new FolixCaseForwardRequest(1L), other));
        assertThrows(FolixReportException.class, () -> service.status(report.getId(), other));
        verify(forwardRepo, never()).saveAndFlush(any(FolixCaseForwardEntity.class));
    }

    @Test
    void unknownReportIsNotFound() {
        FolixReportException e = assertThrows(FolixReportException.class,
                () -> service.forward(UUID.randomUUID(), new FolixCaseForwardRequest(1L), owner));
        assertEquals(FolixReportException.Code.NOT_FOUND, e.code());
    }

    @Test
    void missingFarmerFailsClosed() {
        assertThrows(FolixAuthException.class,
                () -> service.forward(report.getId(), new FolixCaseForwardRequest(1L), null));
        assertThrows(FolixAuthException.class,
                () -> service.forward(report.getId(), new FolixCaseForwardRequest(1L),
                        new AuthenticatedFarmer(null, "x", "x@example.com", NOW)));
        verify(forwardRepo, never()).saveAndFlush(any(FolixCaseForwardEntity.class));
    }

    @Test
    void contactIsRequiredUnknownAndInactiveContactsAreRejected() {
        assertEquals(FolixCaseForwardException.Code.VALIDATION_FAILED,
                assertThrows(FolixCaseForwardException.class,
                        () -> service.forward(report.getId(), null, owner)).code());
        assertEquals(FolixCaseForwardException.Code.VALIDATION_FAILED,
                assertThrows(FolixCaseForwardException.class,
                        () -> service.forward(report.getId(), new FolixCaseForwardRequest(null), owner)).code());
        assertEquals(FolixCaseForwardException.Code.VALIDATION_FAILED,
                assertThrows(FolixCaseForwardException.class,
                        () -> service.forward(report.getId(), new FolixCaseForwardRequest(99L), owner)).code());
        assertEquals(FolixCaseForwardException.Code.VALIDATION_FAILED,
                assertThrows(FolixCaseForwardException.class,
                        () -> service.forward(report.getId(), new FolixCaseForwardRequest(2L), owner)).code());
        verify(forwardRepo, never()).saveAndFlush(any(FolixCaseForwardEntity.class));
    }

    @Test
    void forwardingTwiceIsAConflictAndDoesNotSaveAgain() {
        FolixCaseForwardEntity existing = new FolixCaseForwardEntity(UUID.randomUUID(), report, activeContact,
                FolixForwardStatus.FORWARDED, NOW);
        when(forwardRepo.findByReport_IdAndReport_Farmer_Id(report.getId(), owner.id()))
                .thenReturn(Optional.of(existing));

        FolixCaseForwardException e = assertThrows(FolixCaseForwardException.class,
                () -> service.forward(report.getId(), new FolixCaseForwardRequest(1L), owner));
        assertEquals(FolixCaseForwardException.Code.ALREADY_FORWARDED, e.code());
        verify(forwardRepo, never()).saveAndFlush(any(FolixCaseForwardEntity.class));
    }

    @Test
    void aRaceOnTheUniqueConstraintIsAlsoAConflict() {
        when(forwardRepo.saveAndFlush(any(FolixCaseForwardEntity.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate report_id"));

        FolixCaseForwardException e = assertThrows(FolixCaseForwardException.class,
                () -> service.forward(report.getId(), new FolixCaseForwardRequest(1L), owner));
        assertEquals(FolixCaseForwardException.Code.ALREADY_FORWARDED, e.code());
    }

    @Test
    void statusIsNotForwardedUntilTheOwnerForwards() {
        FolixCaseForwardResponse before = service.status(report.getId(), owner);
        assertEquals("NOT_FORWARDED", before.status());
        assertNull(before.forwardedAt());
        assertNull(before.supportContact());

        FolixCaseForwardEntity existing = new FolixCaseForwardEntity(UUID.randomUUID(), report, activeContact,
                FolixForwardStatus.FORWARDED, NOW);
        when(forwardRepo.findByReport_IdAndReport_Farmer_Id(report.getId(), owner.id()))
                .thenReturn(Optional.of(existing));

        FolixCaseForwardResponse after = service.status(report.getId(), owner);
        assertEquals("FORWARDED", after.status());
        assertEquals(NOW, after.forwardedAt());
    }

    @Test
    void readingStatusNeverCreatesAForward() {
        service.status(report.getId(), owner);
        verify(forwardRepo, never()).saveAndFlush(any(FolixCaseForwardEntity.class));
        verify(forwardRepo, never()).save(any(FolixCaseForwardEntity.class));
    }
}
