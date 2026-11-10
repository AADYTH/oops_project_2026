package com.cropscanner.folix.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the two account repositories against a real (embedded H2) database, so the entity mappings,
 * the unique constraints and the derived queries are checked against actual persistence rather than a mock.
 *
 * NOTE: written but not executed in the environment this stage was built in — see the Stage 6A summary.
 */
@DataJpaTest
class FolixAuthRepositoryTest {

    @Autowired
    private FolixFarmerRepository farmers;

    @Autowired
    private FolixSessionRepository sessions;

    private static FolixFarmerEntity farmer(String email) {
        return new FolixFarmerEntity(UUID.randomUUID(), "Asha Nair", email, "$2a$04$" + "x".repeat(53),
                FolixFarmerStatus.ACTIVE, Instant.now());
    }

    private static FolixSessionEntity session(UUID farmerId, String hash, Instant expiresAt) {
        return new FolixSessionEntity(UUID.randomUUID(), farmerId, hash, Instant.now(), expiresAt);
    }

    @Test
    void savesAndReloadsAFarmerByEmail() {
        FolixFarmerEntity saved = farmers.saveAndFlush(farmer("asha@example.com"));

        FolixFarmerEntity found = farmers.findByEmail("asha@example.com").orElseThrow();

        assertEquals(saved.getId(), found.getId());
        assertEquals("Asha Nair", found.getName());
        assertEquals(FolixFarmerStatus.ACTIVE, found.getStatus());
        assertTrue(farmers.existsByEmail("asha@example.com"));
        assertTrue(farmers.findByEmail("other@example.com").isEmpty());
    }

    @Test
    void theDatabaseItselfRefusesADuplicateEmail() {
        farmers.saveAndFlush(farmer("asha@example.com"));

        assertThrows(DataIntegrityViolationException.class,
                () -> farmers.saveAndFlush(farmer("asha@example.com")));
    }

    @Test
    void sessionsAreLookedUpAndDeletedByTokenHash() {
        UUID farmerId = farmers.saveAndFlush(farmer("asha@example.com")).getId();
        sessions.saveAndFlush(session(farmerId, "a".repeat(64), Instant.now().plus(1, ChronoUnit.DAYS)));

        assertTrue(sessions.findByTokenHash("a".repeat(64)).isPresent());
        assertTrue(sessions.findByTokenHash("b".repeat(64)).isEmpty());

        assertEquals(1L, sessions.deleteByTokenHash("a".repeat(64)));
        assertTrue(sessions.findByTokenHash("a".repeat(64)).isEmpty());
        assertEquals(0L, sessions.deleteByTokenHash("a".repeat(64)));
    }

    @Test
    void theDatabaseRefusesTwoSessionsWithTheSameTokenHash() {
        UUID farmerId = farmers.saveAndFlush(farmer("asha@example.com")).getId();
        sessions.saveAndFlush(session(farmerId, "c".repeat(64), Instant.now().plus(1, ChronoUnit.DAYS)));

        assertThrows(DataIntegrityViolationException.class,
                () -> sessions.saveAndFlush(session(farmerId, "c".repeat(64), Instant.now().plus(1, ChronoUnit.DAYS))));
    }

    @Test
    void housekeepingRemovesOnlyExpiredSessions() {
        UUID farmerId = farmers.saveAndFlush(farmer("asha@example.com")).getId();
        sessions.saveAndFlush(session(farmerId, "d".repeat(64), Instant.now().minus(1, ChronoUnit.HOURS)));
        sessions.saveAndFlush(session(farmerId, "e".repeat(64), Instant.now().plus(1, ChronoUnit.HOURS)));

        assertEquals(1L, sessions.deleteByExpiresAtBefore(Instant.now()));

        assertTrue(sessions.findByTokenHash("d".repeat(64)).isEmpty());
        assertTrue(sessions.findByTokenHash("e".repeat(64)).isPresent());
    }
}
