package com.cropscanner.folix;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 6C architecture guard, in the spirit of {@link FolixReportOwnershipGuardTest}: reads the source tree so a
 * future edit that would quietly leak location or identity fails the build.
 */
class FolixLocationPrivacyGuardTest {

    private static final Path MAIN = Path.of("src", "main", "java", "com", "cropscanner");
    private static final Path FOLIX = MAIN.resolve("folix");

    private static String code(Path file) throws IOException {
        return String.join("\n", Files.readAllLines(file).stream()
                .filter(l -> {
                    String t = l.trim();
                    return !t.startsWith("*") && !t.startsWith("//") && !t.startsWith("/*");
                }).toList());
    }

    @Test
    void noReportDtoExposesLocation() throws IOException {
        for (String name : List.of("FolixReportResponse.java", "FolixReportPage.java", "FolixReportStats.java",
                "FolixReportRequest.java")) {
            String src = code(FOLIX.resolve(name)).toLowerCase();
            for (String word : List.of("region", "latitude", "longitude", "mdeg", "location")) {
                assertFalse(src.contains(word), name + " must not carry location (" + word + ")");
            }
        }
    }

    @Test
    void theMapResponseCarriesNoIdentityAndOnlyCellCentreCoordinates() throws IOException {
        String src = code(FOLIX.resolve("map").resolve("FolixDiseaseMapResponse.java")).toLowerCase()
                .replace("centerlatitude", "").replace("centerlongitude", "");
        for (String word : List.of("farmer", "email", "name", "session", "password", "uuid", "latitude", "longitude")) {
            assertFalse(src.contains(word), "FolixDiseaseMapResponse must not contain: " + word);
        }
    }

    @Test
    void noStoredFieldOrColumnHoldsExactCoordinates() throws IOException {
        for (Path p : List.of(FOLIX.resolve("FolixScanReportEntity.java"),
                FOLIX.resolve("location").resolve("FolixFarmerLocationEntity.java"))) {
            String src = code(p).toLowerCase();
            assertFalse(src.contains("latitude"), p.getFileName() + " must not store an exact latitude");
            assertFalse(src.contains("longitude"), p.getFileName() + " must not store an exact longitude");
            assertTrue(src.contains("mdeg"), p.getFileName() + " should hold the coarse millidegree region");
        }
    }

    @Test
    void theProductionMigrationHasNoExactCoordinateColumn() throws IOException {
        Path sql = Path.of("db", "stage6c-location-disease-map.sql");
        assertTrue(Files.exists(sql), "expected the Stage 6C migration at " + sql.toAbsolutePath());
        String statements = String.join("\n", Files.readAllLines(sql).stream()
                .filter(l -> !l.trim().startsWith("--")).toList()).toLowerCase();
        assertFalse(statements.contains("latitude"));
        assertFalse(statements.contains("longitude"));
        assertTrue(statements.contains("region_lat_mdeg"));
    }

    @Test
    void locationAndMapControllersTakeIdentityOnlyFromTheSession() throws IOException {
        String location = code(FOLIX.resolve("location").resolve("FolixLocationController.java"));
        assertEquals(3, location.split("AuthenticatedFarmer farmer", -1).length - 1,
                "status, enable and disable must each take the AuthenticatedFarmer");
        String map = code(FOLIX.resolve("map").resolve("FolixDiseaseMapController.java"));
        for (String src : List.of(location, map)) {
            for (String bad : List.of("@RequestParam", "@PathVariable", "@RequestHeader", "@CookieValue")) {
                assertFalse(src.contains(bad), "must not read anything from the request except the body/session: " + bad);
            }
        }
        assertFalse(map.contains("AuthenticatedFarmer"), "the aggregated map is public and farmer-independent");
    }

    @Test
    void theMapRepositoryReadsOnlyTheNarrowAnonymousProjection() throws IOException {
        String src = code(FOLIX.resolve("map").resolve("FolixDiseaseMapRepository.java")).toLowerCase();
        for (String word : List.of("email", "password", "r.reasoning", "symptoms", "recommendedactions")) {
            assertFalse(src.contains(word), "the map query must not select: " + word);
        }
        assertTrue(src.contains("extends repository<"), "a bare Repository: no inherited findAll/delete");
    }

    @Test
    void theLocationCodeNeverLogsRequestDataOrCoordinates() throws IOException {
        String service = code(FOLIX.resolve("location").resolve("FolixLocationService.java"));
        assertFalse(service.contains("Logger") || service.contains("System.out"),
                "FolixLocationService handles the raw coordinates and must not log at all");

        String handler = code(FOLIX.resolve("location").resolve("FolixLocationExceptionHandler.java"));
        for (String line : handler.split("\n")) {
            if (line.contains("log.") || line.contains("System.out")) {
                String l = line.toLowerCase();
                assertFalse(l.contains("request") || l.contains("latitude") || l.contains("longitude")
                        || l.contains("body"), "the handler may log a failure, never request data: " + line);
            }
        }
    }

    @Test
    void theLegacyOutbreakApiAndAuthorityPortalAreUntouched() {
        for (String f : List.of("controller/OutbreakController.java", "service/OutbreakService.java",
                "controller/AuthorityController.java", "service/AuthoritySelfServiceService.java")) {
            assertTrue(Files.exists(MAIN.resolve(f)), "expected legacy file to still exist: " + f);
        }
    }

    @Test
    void newFolixCodeDoesNotDependOnLegacyClasses() throws IOException {
        try (Stream<Path> files = Files.walk(FOLIX.resolve("location"))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                assertFalse(code(p).contains("com.cropscanner.model.") || code(p).contains("com.cropscanner.entity."),
                        p.getFileName() + " must not use the legacy model/entity packages");
            }
        }
        try (Stream<Path> files = Files.walk(FOLIX.resolve("map"))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                assertFalse(code(p).contains("com.cropscanner.model.") || code(p).contains("com.cropscanner.entity."),
                        p.getFileName() + " must not use the legacy model/entity packages");
            }
        }
    }
}
