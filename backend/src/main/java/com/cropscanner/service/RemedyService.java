package com.cropscanner.service;

import com.cropscanner.model.DiseaseInfo;

import java.util.Optional;

/**
 * Looks up organic and chemical treatment guidance for a diagnosed disease.
 * Backed by a curated, factual knowledge base (see
 * {@code resources/remedies.json}) sourced from standard agricultural
 * extension guidance — not generated per-request and not a placeholder.
 */
public interface RemedyService {

    /**
     * @param diseaseName exact disease name as returned by the classifier
     * @return the remedy plan for that disease, or empty if it is not yet in the knowledge base
     */
    Optional<DiseaseInfo.RemedyPlan> findRemedyPlan(String diseaseName);
}
