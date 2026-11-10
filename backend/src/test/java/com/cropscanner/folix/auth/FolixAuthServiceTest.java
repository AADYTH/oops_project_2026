package com.cropscanner.folix.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;

/**
 * Service-level tests for registration, password hashing, login, logout and session resolution.
 * The repositories are in-memory stand-ins (see {@link AuthTestSupport.Store}); the password encoder is
 * the REAL BCrypt implementation, so "the password is hashed" is verified against real hashes.
 */
class FolixAuthServiceTest {

    private static final String PASSWORD = "correct horse 42";

    private AuthTestSupport.Store store;
    private AuthTestSupport.MutableClock clock;
    private PasswordEncoder encoder;
    private FolixAuthService service;

    @BeforeEach
    void setUp() {
        store = new AuthTestSupport.Store();
        clock = new AuthTestSupport.MutableClock();
        encoder = AuthTestSupport.encoder();
        service = new FolixAuthService(store.farmerRepo, store.sessionRepo, encoder, AuthTestSupport.settings(), clock);
    }

    private FarmerProfileResponse registerAsha() {
        return service.register(new RegisterRequest("Asha Nair", "asha@example.com", PASSWORD));
    }

    private static FolixAuthException.Code codeOf(Runnable action) {
        return assertThrows(FolixAuthException.class, action::run).code();
    }

    // ------------------------------------------------------------------
    // successful registration
    // ------------------------------------------------------------------

    @Test
    void registrationCreatesAnActiveAccountWithNormalizedNameAndEmail() {
        FarmerProfileResponse created = service.register(
                new RegisterRequest("  Asha   Nair ", "  Asha.Nair@Example.COM ", PASSWORD));

        assertEquals("Asha Nair", created.name());
        assertEquals("asha.nair@example.com", created.email());
        assertEquals(clock.instant(), created.createdAt());
        assertEquals(1, store.farmers.size());
        assertEquals(FolixFarmerStatus.ACTIVE, store.farmers.get(created.id()).getStatus());
    }

    @Test
    void registrationResponseNeverContainsPasswordMaterial() {
        Set<String> components = Arrays.stream(FarmerProfileResponse.class.getRecordComponents())
                .map(c -> c.getName()).collect(Collectors.toSet());
        assertEquals(Set.of("id", "name", "email", "createdAt"), components);
    }

    // ------------------------------------------------------------------
    // password hashing
    // ------------------------------------------------------------------

    @Test
    void passwordIsStoredAsAVerifiableBCryptHashNeverPlaintext() {
        FarmerProfileResponse created = registerAsha();
        String stored = store.farmers.get(created.id()).getPasswordHash();

        assertNotEquals(PASSWORD, stored);
        assertFalse(stored.contains(PASSWORD));
        assertTrue(stored.startsWith("$2"), "expected a BCrypt hash, got a different format");
        assertTrue(encoder.matches(PASSWORD, stored));
        assertFalse(encoder.matches("some other password", stored));
    }

    @Test
    void samePasswordForTwoFarmersProducesDifferentHashes() {
        FarmerProfileResponse a = service.register(new RegisterRequest("A", "a@example.com", PASSWORD));
        FarmerProfileResponse b = service.register(new RegisterRequest("B", "b@example.com", PASSWORD));

        assertNotEquals(store.farmers.get(a.id()).getPasswordHash(), store.farmers.get(b.id()).getPasswordHash());
    }

    @Test
    void requestObjectsDoNotPrintThePassword() {
        assertFalse(new RegisterRequest("A", "a@example.com", PASSWORD).toString().contains(PASSWORD));
        assertFalse(new LoginRequest("a@example.com", PASSWORD).toString().contains(PASSWORD));
    }

    // ------------------------------------------------------------------
    // duplicate registration
    // ------------------------------------------------------------------

    @Test
    void duplicateEmailIsRejectedCaseInsensitivelyAndCreatesNothing() {
        registerAsha();

        FolixAuthException e = assertThrows(FolixAuthException.class,
                () -> service.register(new RegisterRequest("Someone Else", "ASHA@Example.com", "another pass 99")));

        assertEquals(FolixAuthException.Code.EMAIL_ALREADY_REGISTERED, e.code());
        assertEquals(409, e.code().httpStatus());
        assertEquals(1, store.farmers.size());
    }

    @Test
    void aConcurrentDuplicateCaughtByTheDatabaseConstraintIsAlsoReportedAsDuplicate() {
        Mockito.doThrow(new DataIntegrityViolationException("uk_folix_farmer_email"))
                .when(store.farmerRepo).saveAndFlush(any(FolixFarmerEntity.class));

        assertEquals(FolixAuthException.Code.EMAIL_ALREADY_REGISTERED,
                codeOf(() -> service.register(new RegisterRequest("Race", "race@example.com", PASSWORD))));
    }

    // ------------------------------------------------------------------
    // invalid registration
    // ------------------------------------------------------------------

    @Test
    void invalidRegistrationsAreRejectedAndNothingIsPersisted() {
        String[][] invalid = {
                {null, "a@example.com", PASSWORD},                 // missing name
                {"   ", "a@example.com", PASSWORD},                // blank name
                {"Asha", null, PASSWORD},                          // missing email
                {"Asha", "not-an-email", PASSWORD},
                {"Asha", "a@localhost", PASSWORD},                 // no dot in the domain
                {"Asha", "a b@example.com", PASSWORD},             // whitespace inside
                {"Asha", ".a@example.com", PASSWORD},              // leading dot
                {"Asha", "a..b@example.com", PASSWORD},            // consecutive dots
                {"Asha", "a@-example.com", PASSWORD},              // bad domain label
                {"Asha", "a@example.com", null},                   // missing password
                {"Asha", "a@example.com", "short1"},               // < 8 chars
                {"Asha", "a@example.com", "         "},            // only spaces
                {"Asha", "a@example.com", "x".repeat(73)},         // > 72 bytes (BCrypt limit)
                {"Asha", "a@example.com", "\u00e9".repeat(37)},    // 74 bytes in UTF-8
                {"Asha", "same@example.com", "SAME@example.com"},  // password equals email
                {"x".repeat(101), "a@example.com", PASSWORD},      // name too long
                {"Bad\u0007Name", "a@example.com", PASSWORD},      // control character
        };
        for (String[] r : invalid) {
            assertEquals(FolixAuthException.Code.VALIDATION_FAILED,
                    codeOf(() -> service.register(new RegisterRequest(r[0], r[1], r[2]))),
                    "should have been rejected: " + Arrays.toString(r));
        }
        assertTrue(store.farmers.isEmpty());
    }

    @Test
    void aMissingRequestBodyIsAValidationErrorNotAServerError() {
        assertEquals(FolixAuthException.Code.VALIDATION_FAILED, codeOf(() -> service.register(null)));
    }

    @Test
    void everyBadFieldIsReportedAtOnce() {
        FolixAuthException e = assertThrows(FolixAuthException.class,
                () -> service.register(new RegisterRequest("", "nope", "x")));
        assertEquals(Set.of("name", "email", "password"), e.fields().keySet());
    }

    @Test
    void passwordLengthBoundariesAreAccepted() {
        service.register(new RegisterRequest("Min", "min@example.com", "12345678"));
        service.register(new RegisterRequest("Max", "max@example.com", "y".repeat(72)));
        assertEquals(2, store.farmers.size());
    }

    // ------------------------------------------------------------------
    // successful login
    // ------------------------------------------------------------------

    @Test
    void loginWithTheRightPasswordStartsASessionWhoseTokenIsStoredOnlyAsAHash() {
        FarmerProfileResponse created = registerAsha();

        FolixAuthService.LoginResult login = service.login(new LoginRequest(" ASHA@example.com ", PASSWORD));

        assertEquals(created.id(), login.farmer().id());
        assertTrue(login.sessionToken().matches("[A-Za-z0-9_-]{43}"));
        assertEquals(clock.instant().plus(Duration.ofHours(168)), login.expiresAt());
        assertEquals(1, store.sessions.size());
        assertTrue(store.sessions.containsKey(FolixAuthService.sha256Hex(login.sessionToken())));
        assertFalse(store.sessions.containsKey(login.sessionToken()), "the raw token must not be stored");
        assertFalse(login.toString().contains(login.sessionToken()));
    }

    @Test
    void everyLoginGetsItsOwnToken() {
        registerAsha();
        String first = service.login(new LoginRequest("asha@example.com", PASSWORD)).sessionToken();
        String second = service.login(new LoginRequest("asha@example.com", PASSWORD)).sessionToken();
        assertNotEquals(first, second);
    }

    // ------------------------------------------------------------------
    // invalid login
    // ------------------------------------------------------------------

    @Test
    void wrongPasswordAndUnknownEmailGetTheSameGenericFailure() {
        registerAsha();

        FolixAuthException wrong = assertThrows(FolixAuthException.class,
                () -> service.login(new LoginRequest("asha@example.com", "wrong password")));
        FolixAuthException unknown = assertThrows(FolixAuthException.class,
                () -> service.login(new LoginRequest("nobody@example.com", "wrong password")));

        assertEquals(FolixAuthException.Code.INVALID_CREDENTIALS, wrong.code());
        assertEquals(401, wrong.code().httpStatus());
        assertEquals(wrong.code(), unknown.code());
        assertEquals(wrong.getMessage(), unknown.getMessage(), "the message must not reveal whether the account exists");
        assertTrue(store.sessions.isEmpty(), "a failed login must not create a session");
    }

    @Test
    void anOverlongPasswordIsAnInvalidLoginNotAnError() {
        registerAsha();
        assertEquals(FolixAuthException.Code.INVALID_CREDENTIALS,
                codeOf(() -> service.login(new LoginRequest("asha@example.com", "z".repeat(500)))));
    }

    @Test
    void blankLoginFieldsAreAValidationError() {
        assertEquals(FolixAuthException.Code.VALIDATION_FAILED, codeOf(() -> service.login(new LoginRequest(" ", ""))));
        assertEquals(FolixAuthException.Code.VALIDATION_FAILED, codeOf(() -> service.login(null)));
    }

    @Test
    void aDisabledAccountCannotLogInAndItsSessionsStopWorking() {
        FarmerProfileResponse created = registerAsha();
        String token = service.login(new LoginRequest("asha@example.com", PASSWORD)).sessionToken();
        FolixFarmerEntity original = store.farmers.get(created.id());
        store.farmers.put(created.id(), new FolixFarmerEntity(original.getId(), original.getName(), original.getEmail(),
                original.getPasswordHash(), FolixFarmerStatus.DISABLED, original.getCreatedAt()));

        assertTrue(service.authenticate(token).isEmpty());
        assertEquals(FolixAuthException.Code.INVALID_CREDENTIALS,
                codeOf(() -> service.login(new LoginRequest("asha@example.com", PASSWORD))));
    }

    // ------------------------------------------------------------------
    // authenticate / logout / expiry
    // ------------------------------------------------------------------

    @Test
    void aValidTokenResolvesToTheRightFarmer() {
        FarmerProfileResponse created = registerAsha();
        String token = service.login(new LoginRequest("asha@example.com", PASSWORD)).sessionToken();

        Optional<AuthenticatedFarmer> who = service.authenticate(token);

        assertTrue(who.isPresent());
        assertEquals(created.id(), who.get().id());
        assertEquals("asha@example.com", who.get().email());
    }

    @Test
    void missingUnknownMalformedAndHashedTokensAreNotAuthenticated() {
        registerAsha();
        String token = service.login(new LoginRequest("asha@example.com", PASSWORD)).sessionToken();

        assertTrue(service.authenticate(null).isEmpty());
        assertTrue(service.authenticate("").isEmpty());
        assertTrue(service.authenticate("not a token!").isEmpty());
        assertTrue(service.authenticate("A".repeat(43)).isEmpty());
        assertTrue(service.authenticate("A".repeat(5000)).isEmpty());
        assertTrue(service.authenticate(FolixAuthService.sha256Hex(token)).isEmpty(),
                "knowing the stored hash must not be enough to authenticate");
    }

    @Test
    void logoutRevokesOnlyThatSessionAndIsIdempotent() {
        registerAsha();
        String first = service.login(new LoginRequest("asha@example.com", PASSWORD)).sessionToken();
        String second = service.login(new LoginRequest("asha@example.com", PASSWORD)).sessionToken();

        service.logout(first);

        assertTrue(service.authenticate(first).isEmpty(), "a logged-out token must stop working");
        assertTrue(service.authenticate(second).isPresent(), "another session is unaffected");
        service.logout(first);
        service.logout(null);
        service.logout("junk");
    }

    @Test
    void aSessionExpiresAfterItsLifetimeAndTheRowIsRemoved() {
        registerAsha();
        String token = service.login(new LoginRequest("asha@example.com", PASSWORD)).sessionToken();

        clock.advance(Duration.ofHours(167));
        assertTrue(service.authenticate(token).isPresent());

        clock.advance(Duration.ofHours(2));
        assertTrue(service.authenticate(token).isEmpty());
        assertTrue(store.sessions.isEmpty());
    }
}
