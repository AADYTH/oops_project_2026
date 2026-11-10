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
 * Stage 6B architecture guard, in the same spirit as {@link FolixIsolationTest}: reads the source tree so a future
 * edit that would quietly break report ownership fails the build.
 *
 * <ul>
 *   <li>the report service never uses an unscoped repository method (those return everyone's reports);</li>
 *   <li>every farmer-facing repository query is owner-scoped;</li>
 *   <li>no response/request DTO carries an owner field;</li>
 *   <li>the legacy CropGuard / authority code neither uses nor depends on the FOLIX report classes, so the
 *       authority dashboard is unaffected by farmer ownership.</li>
 * </ul>
 */
class FolixReportOwnershipGuardTest {

    private static final Path MAIN = Path.of("src", "main", "java", "com", "cropscanner");
    private static final Path FOLIX = MAIN.resolve("folix");

    /** Source with Javadoc/line comments removed, so prose that merely mentions a method name is not a "call". */
    private static String code(Path file) throws IOException {
        return String.join("\n", Files.readAllLines(file).stream()
                .filter(l -> {
                    String t = l.trim();
                    return !t.startsWith("*") && !t.startsWith("//") && !t.startsWith("/*");
                }).toList());
    }

    @Test
    void theReportServiceNeverCallsAnUnscopedRepositoryMethod() throws IOException {
        String service = code(FOLIX.resolve("FolixReportService.java"));
        for (String forbidden : List.of(
                "repository.findById(", "repository.findAll(", "repository.findAllById(", "repository.count(",
                "repository.existsById(", "repository.getReferenceById(", "repository.getById(",
                "repository.deleteById(", "repository.deleteAll(",
                "findAllByOrderByCreatedAtDesc", "countByNeedsHumanReviewTrue", "countByStatus")) {
            assertFalse(service.contains(forbidden),
                    "FolixReportService must only use owner-scoped queries, but it uses: " + forbidden);
        }
        // and it does use the scoped ones
        for (String required : List.of("findAllByFarmer_IdOrderByCreatedAtDesc", "findByIdAndFarmer_Id",
                "countByFarmer_Id(", "countByFarmer_IdAndNeedsHumanReviewTrue")) {
            assertTrue(service.contains(required), "FolixReportService should use " + required);
        }
    }

    @Test
    void everyFarmerFacingQueryOnTheReportRepositoryIsOwnerScoped() throws IOException {
        List<String> declared = Files.readAllLines(FOLIX.resolve("FolixScanReportRepository.java")).stream()
                .map(String::trim)
                .filter(l -> l.matches("(Page<.*>|Optional<.*>|long|List<.*>) \\w+\\(.*\\);"))
                .toList();
        assertTrue(declared.size() >= 4, "expected the scoped queries to be declared: " + declared);
        for (String line : declared) {
            boolean scoped = line.contains("Farmer_Id");
            boolean adminCountOnly = line.contains(" countByStatus(");   // unexposed, used by no service/controller
            assertTrue(scoped || adminCountOnly, "unscoped report query declared on the repository: " + line);
        }
    }

    @Test
    void noReportDtoOrRequestCarriesOwnerInformation() throws IOException {
        for (String name : List.of("FolixReportResponse.java", "FolixReportPage.java", "FolixReportStats.java",
                "FolixReportRequest.java")) {
            String src = code(FOLIX.resolve(name)).toLowerCase();
            for (String word : List.of("farmer", "owner", "userid", "user_id", "email")) {
                assertFalse(src.contains(word), name + " must not contain owner information (" + word + ")");
            }
        }
    }

    @Test
    void theControllerTakesTheFarmerOnlyFromTheResolvedSession() throws IOException {
        String controller = code(FOLIX.resolve("FolixReportController.java"));
        // every route takes the AuthenticatedFarmer parameter...
        assertEquals(4, controller.split("AuthenticatedFarmer farmer", -1).length - 1,
                "save, list, summary and getById must each take the AuthenticatedFarmer");
        // ...and nothing owner-like is bound from the request
        for (String bad : List.of("@RequestParam(\"farmerId\"", "@RequestParam(value = \"farmerId\"",
                "@RequestHeader", "@PathVariable(\"farmerId\"", "@CookieValue")) {
            assertFalse(controller.contains(bad), "the controller must not read an owner from the request: " + bad);
        }
    }

    @Test
    void legacyAndAuthorityCodeDoesNotTouchTheFolixReportClasses() throws IOException {
        List<String> touching = new java.util.ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (p.startsWith(FOLIX)) continue;
                String src = code(p);
                if (src.contains("FolixScanReport") || src.contains("FolixReportService")
                        || src.contains("FolixReportController")) {
                    touching.add(p.toString());
                }
            }
        }
        assertTrue(touching.isEmpty(),
                "code outside the folix package must not depend on the FOLIX report classes: " + touching);
    }

    @Test
    void theAuthorityPortalStillHasItsOwnUnchangedFiles() {
        // The authority dashboard + its API are separate legacy code; Stage 6B does not own or edit them.
        for (String f : List.of("controller/AuthorityController.java", "service/AuthoritySelfServiceService.java",
                "repository/AuthorityJpaRepository.java", "entity/AuthorityEntity.java")) {
            assertTrue(Files.exists(MAIN.resolve(f)), "expected legacy authority file to still exist: " + f);
        }
    }
}
