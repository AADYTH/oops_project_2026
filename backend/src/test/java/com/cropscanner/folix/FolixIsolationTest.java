package com.cropscanner.folix;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Architecture guard: the FOLIX Scanner path must not depend on the legacy CropGuard diagnosis pipeline
 * (local ML inference, ONNX/PlantVillage/PlantDoc classifier, OpenCV scoring, second-opinion confidence
 * blending). Reads the source tree so a stray import fails the build.
 */
class FolixIsolationTest {

    private static final Path SRC = Path.of("src", "main", "java", "com", "cropscanner", "folix");

    private static final List<String> FORBIDDEN_IMPORT_FRAGMENTS = List.of(
            "com.cropscanner.service.InferenceService",
            "com.cropscanner.service.CropScannerService",
            "com.cropscanner.service.impl.RemoteMlInferenceService",
            "com.cropscanner.service.GeminiVisionService",
            "com.cropscanner.service.impl.RestGeminiVisionService",
            "com.cropscanner.service.EvidenceConfidenceService",
            "com.cropscanner.service.impl.NoLeafDetectedException",
            "com.cropscanner.model.ScanAnalysisResult",
            "com.cropscanner.model.GeminiOpinion",
            "com.cropscanner.dto.",
            "com.cropscanner.entity.",
            "com.cropscanner.repository.");

    private static List<Path> sources() throws IOException {
        try (Stream<Path> s = Files.list(SRC)) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    @Test
    void scannerSourcesExist() throws IOException {
        assertTrue(sources().size() >= 10, "expected the folix package at " + SRC.toAbsolutePath());
    }

    @Test
    void noScannerClassImportsTheLegacyDiagnosisPipeline() throws IOException {
        for (Path p : sources()) {
            for (String line : Files.readAllLines(p)) {
                if (!line.startsWith("import ")) continue;
                for (String bad : FORBIDDEN_IMPORT_FRAGMENTS) {
                    assertFalse(line.contains(bad), p.getFileName() + " must not import " + bad);
                }
            }
        }
    }

    @Test
    void onlyTheWiringConfigTouchesTheLegacyKeyPool() throws IOException {
        for (Path p : sources()) {
            boolean imports = Files.readAllLines(p).stream()
                    .anyMatch(l -> l.startsWith("import ") && l.contains("GeminiKeyPool"));
            assertEquals(p.getFileName().toString().equals("FolixScannerConfig.java"), imports,
                    p.getFileName() + " GeminiKeyPool import");
        }
    }

    @Test
    void noScannerCodeCallsTheRotatingKeySelector() throws IOException {
        for (Path p : sources()) {
            String code = String.join("\n", Files.readAllLines(p).stream()
                    .filter(l -> !l.trim().startsWith("*") && !l.trim().startsWith("//") && !l.trim().startsWith("/*"))
                    .toList());
            assertFalse(code.contains("selectAvailableKey"), p.getFileName() + " must not use round-robin selection");
            assertFalse(code.contains("markUnavailable"), p.getFileName() + " must not drive key cooldowns");
        }
    }
}
