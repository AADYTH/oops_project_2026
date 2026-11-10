package com.cropscanner.folix;

import com.cropscanner.folix.DiagnosisResult.Confidence;
import com.cropscanner.folix.DiagnosisResult.PlantPart;
import com.cropscanner.folix.DiagnosisResult.Severity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Converts Pl@ntNet's real, documented species-identification response (see
 * my.plantnet.org/doc/api/identify) into a {@link DiagnosisResult} — deliberately separate from
 * {@link AssessmentParser}, which is Gemini-schema-specific and must not be reused for Pl@ntNet's
 * different JSON shape.
 *
 * BUGFIX (Issue #5): Pl@ntNet identifies plant SPECIES; it does not diagnose plant diseases. This
 * mapper used to read a {@code disease.label}/{@code disease.name} field (from an unverified,
 * undocumented endpoint — see {@link PlantNetSpeciesClient}'s class doc) and reported it as the
 * {@code disease} field, which is exactly the "species ID treated as a disease diagnosis" mistake
 * the project brief warns against. It now reads the real response shape — a ranked list of
 * candidate species, each with a scientific name, common names and a match score — and:
 *  - {@code disease} is ALWAYS {@link DiagnosisResult#UNKNOWN}: nothing in this response is a
 *    disease diagnosis, so nothing here is ever allowed to look like one;
 *  - {@code confidence} is ALWAYS {@link Confidence#LOW}: the match score describes how sure
 *    Pl@ntNet is about the SPECIES, which says nothing about disease confidence, so it must never
 *    be reused as if it were one (this keeps {@code disease unknown -> confidence LOW} in the same
 *    invariant {@link AssessmentParser} enforces for Gemini results, and keeps the frontend's
 *    uncertainty routing honest for this path too);
 *  - {@code crop} carries the identified species (a common name if Pl@ntNet returned one, else the
 *    scientific name) — this is genuinely what Pl@ntNet told us, so it is safe to surface;
 *  - {@code reasoning} names the identified species and its match score plainly, and states
 *    outright that Pl@ntNet does not diagnose diseases;
 *  - {@code needsHumanReview} is always true;
 *  - an empty results list is a failure (ANALYSIS_UNAVAILABLE), never a fabricated answer.
 */
@Component
public class PlantNetDiagnosisMapper {

    static final String FALLBACK_REASONING_TEMPLATE =
            "Gemini's primary disease analysis was unavailable, so this result comes from the Pl@ntNet "
                    + "fallback. Pl@ntNet identified the plant as %s (match confidence %.0f%%), but Pl@ntNet "
                    + "identifies plant species only — it does not diagnose diseases, so no disease could be "
                    + "determined for this scan.";
    static final String FALLBACK_REASONING_NO_SPECIES =
            "Gemini's primary disease analysis was unavailable, and the Pl@ntNet fallback could not confidently "
                    + "identify the plant species either. Pl@ntNet identifies plant species only — it does not "
                    + "diagnose diseases, so no disease could be determined for this scan.";

    private final ObjectMapper mapper = new ObjectMapper();

    public DiagnosisResult map(String plantNetResponseText) {
        JsonNode root = readRoot(plantNetResponseText);

        JsonNode results = root.get("results");
        if (results == null || !results.isArray() || results.isEmpty()) {
            throw FolixAnalysisException.unavailable("Pl@ntNet returned no identification results");
        }

        JsonNode top = results.get(0);
        double score = top.path("score").isNumber() ? top.path("score").asDouble() : -1;
        JsonNode species = top.get("species");

        String scientificName = textOrNull(species, "scientificNameWithoutAuthor");
        if (scientificName == null) scientificName = textOrNull(species, "scientificName");
        String commonName = firstCommonName(species);
        String speciesLabel = commonName != null ? commonName : scientificName;

        String crop = speciesLabel != null
                ? AssessmentParser.normalizeName(speciesLabel, AssessmentParser.MAX_CROP)
                : DiagnosisResult.UNKNOWN;
        String reasoning = speciesLabel != null
                ? String.format(java.util.Locale.ROOT, FALLBACK_REASONING_TEMPLATE, speciesLabel, Math.max(score, 0) * 100)
                : FALLBACK_REASONING_NO_SPECIES;

        return new DiagnosisResult(
                true,
                PlantPart.UNKNOWN,
                crop,
                DiagnosisResult.UNKNOWN,
                Severity.UNKNOWN,
                Confidence.LOW,
                List.of(),
                reasoning,
                List.of(),
                true);
    }

    private static String firstCommonName(JsonNode species) {
        if (species == null) return null;
        JsonNode names = species.get("commonNames");
        if (names == null || !names.isArray() || names.isEmpty()) return null;
        JsonNode first = names.get(0);
        return (first != null && first.isTextual() && !first.textValue().isBlank()) ? first.textValue() : null;
    }

    private JsonNode readRoot(String text) {
        if (text == null || text.isBlank()) {
            throw malformed("empty Pl@ntNet response");
        }
        JsonNode root;
        try {
            root = mapper.readTree(text);
        } catch (JsonProcessingException e) {
            throw malformed("Pl@ntNet response is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw malformed("Pl@ntNet response is not a JSON object");
        }
        return root;
    }

    private static String textOrNull(JsonNode parent, String field) {
        if (parent == null) return null;
        JsonNode n = parent.get(field);
        return (n != null && n.isTextual() && !n.textValue().isBlank()) ? n.textValue() : null;
    }

    private static FolixAnalysisException malformed(String internalDetail) {
        return FolixAnalysisException.failed("malformed Pl@ntNet response: " + internalDetail, true);
    }
}
