package com.cropscanner.folix;

import com.cropscanner.model.DiseaseInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Stores a nullable {@link DiseaseInfo.RemedyPlan} (FOLIX report {@code remedyPlan}) as a single JSON
 * object column, or {@code NULL} when no matching remedy data was found for the diagnosed disease — that
 * absence is meaningful (see {@link FolixScanService}) and must never be turned into a fabricated plan on
 * read-back.
 */
@Converter
class RemedyPlanJsonConverter implements AttributeConverter<DiseaseInfo.RemedyPlan, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(DiseaseInfo.RemedyPlan attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize FOLIX report remedy plan", e);
        }
    }

    @Override
    public DiseaseInfo.RemedyPlan convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(dbData, DiseaseInfo.RemedyPlan.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize FOLIX report remedy plan", e);
        }
    }
}
