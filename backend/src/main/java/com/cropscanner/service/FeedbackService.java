package com.cropscanner.service;

import com.cropscanner.dto.FeedbackDtos;
import com.cropscanner.entity.ScanFeedbackEntity;
import com.cropscanner.repository.ScanFeedbackJpaRepository;
import org.springframework.stereotype.Service;

/**
 * Tier 1 upgrade #2: turns user/agronomist corrections into a real,
 * exportable labelled dataset instead of a wrong diagnosis just being
 * forgotten. See {@link ScanFeedbackEntity} for the important caveat that
 * this collects data — it does not automatically retrain anything.
 */
@Service
public class FeedbackService {

    private final ScanFeedbackJpaRepository repository;

    public FeedbackService(ScanFeedbackJpaRepository repository) {
        this.repository = repository;
    }

    public FeedbackDtos.FeedbackAck submit(FeedbackDtos.SubmitFeedbackRequest req) {
        ScanFeedbackEntity entity = new ScanFeedbackEntity(
                req.scanId(),
                req.originalCropName(),
                req.originalDiseaseName(),
                req.originalConfidencePercent(),
                req.wasCorrect(),
                req.correctedCropName(),
                req.correctedDiseaseName(),
                req.note()
        );
        entity = repository.save(entity);
        return new FeedbackDtos.FeedbackAck(entity.getId(),
                "Thanks — this correction has been recorded and will help improve future diagnoses.");
    }
}
