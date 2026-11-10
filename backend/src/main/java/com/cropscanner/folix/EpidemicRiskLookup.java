package com.cropscanner.folix;

import com.cropscanner.service.DiseaseNameMatcher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Whether a disease is inherently dangerous — fast-spreading and/or quarantine-notifiable — regardless of
 * how much of THIS plant is currently affected. That is a property of the disease itself, not of one
 * scan, so it is read from a maintained lookup table ({@code epidemic-risk-diseases.json}) rather than
 * ever being taken from the model's own opinion: a free-text classifier has no reason to be calibrated
 * about epidemic risk, and trusting it would make the flag unauditable.
 *
 * Deliberately separate from {@code remedies.json}/{@code JsonRemedyService}: they are curated by
 * different concerns (treatment guidance vs. spread/quarantine risk) and a disease can be in one
 * knowledge base without (yet) being in the other. Both use the same {@link DiseaseNameMatcher} rules so
 * a classifier's free-text disease name is reconciled identically in both places.
 *
 * A disease that has no entry at all is treated as {@code false} (not flagged) — the safe default for
 * "not yet audited", never assumed to be dangerous and never assumed to be safe based on the model saying so.
 */
@Component
public class EpidemicRiskLookup {

    private static final Logger log = LoggerFactory.getLogger(EpidemicRiskLookup.class);

    private final Map<String, Boolean> epidemicRiskByDisease = new HashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    void loadLookupTable() {
        try (InputStream is = new ClassPathResource("epidemic-risk-diseases.json").getInputStream()) {
            JsonNode root = objectMapper.readTree(is);
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (entry.getKey().startsWith("_")) {
                    continue; // e.g. "_comment" — documentation, not a disease entry
                }
                epidemicRiskByDisease.put(entry.getKey(), entry.getValue().asBoolean(false));
            }
            log.info("Loaded epidemic-risk lookup table with {} disease entries", epidemicRiskByDisease.size());
        } catch (Exception e) {
            throw new IllegalStateException("Could not load epidemic-risk-diseases.json at startup", e);
        }
    }

    /**
     * @param diseaseName the classifier's disease name, exact spelling not required
     * @return true only if this exact disease, or an unambiguous fuzzy match to one, is on the
     *         epidemic-risk watchlist; false for anything unmatched or explicitly marked safe
     */
    public boolean isEpidemicRisk(String diseaseName) {
        if (diseaseName == null || diseaseName.isBlank()) {
            return false;
        }
        Boolean exact = epidemicRiskByDisease.get(diseaseName);
        if (exact != null) {
            return exact;
        }
        Boolean matched = DiseaseNameMatcher.match(diseaseName, epidemicRiskByDisease);
        return matched != null && matched;
    }
}
