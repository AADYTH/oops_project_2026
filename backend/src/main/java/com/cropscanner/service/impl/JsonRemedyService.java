package com.cropscanner.service.impl;

import com.cropscanner.model.DiseaseInfo;
import com.cropscanner.service.DiseaseNameMatcher;
import com.cropscanner.service.RemedyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

@Service
public class JsonRemedyService implements RemedyService {

    private static final Logger log = LoggerFactory.getLogger(JsonRemedyService.class);

    private final Map<String, DiseaseInfo.RemedyPlan> remedyPlansByDisease = new HashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    void loadKnowledgeBase() {
        try (InputStream is = new ClassPathResource("remedies.json").getInputStream()) {
            JsonNode root = objectMapper.readTree(is);
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                remedyPlansByDisease.put(entry.getKey(), parseRemedyPlan(entry.getValue()));
            }
            log.info("Loaded remedy knowledge base with {} disease entries", remedyPlansByDisease.size());
        } catch (Exception e) {
            throw new IllegalStateException("Could not load remedies.json knowledge base at startup", e);
        }
    }

    /**
     * Looks up {@code diseaseName} in the knowledge base. An exact key match is tried first (the legacy
     * CropGuard pipeline's {@code DiseaseInfo} names always match exactly); if that misses, the name is
     * normalized and fuzzy-matched via {@link DiseaseNameMatcher} against the curated {@code remedies.json}
     * keys, since a free-text classifier's naming (e.g. Gemini in the FOLIX pipeline) may not match a key's
     * exact spelling. A miss at every stage returns empty — never a fabricated or best-guess plan.
     */
    @Override
    public Optional<DiseaseInfo.RemedyPlan> findRemedyPlan(String diseaseName) {
        if (diseaseName == null || diseaseName.isBlank()) {
            return Optional.empty();
        }
        DiseaseInfo.RemedyPlan exact = remedyPlansByDisease.get(diseaseName);
        if (exact != null) {
            return Optional.of(exact);
        }
        return Optional.ofNullable(DiseaseNameMatcher.match(diseaseName, remedyPlansByDisease));
    }

    private DiseaseInfo.RemedyPlan parseRemedyPlan(JsonNode node) {
        java.util.List<DiseaseInfo.OrganicRemedy> organic = new java.util.ArrayList<>();
        for (JsonNode o : node.path("organicRemedies")) {
            organic.add(new DiseaseInfo.OrganicRemedy(
                    o.get("name").asText(),
                    o.get("instructions").asText(),
                    o.get("applicationFrequency").asText()));
        }

        java.util.List<DiseaseInfo.ChemicalRemedy> chemical = new java.util.ArrayList<>();
        for (JsonNode c : node.path("chemicalRemedies")) {
            chemical.add(new DiseaseInfo.ChemicalRemedy(
                    c.get("name").asText(),
                    c.get("activeIngredient").asText(),
                    c.get("instructions").asText(),
                    c.get("dosage").asText(),
                    c.get("preHarvestIntervalDays").asText(),
                    c.get("safetyPrecautions").asText()));
        }

        return new DiseaseInfo.RemedyPlan(organic, chemical);
    }
}
