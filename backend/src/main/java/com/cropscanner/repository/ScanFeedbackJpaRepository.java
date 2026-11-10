package com.cropscanner.repository;

import com.cropscanner.entity.ScanFeedbackEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ScanFeedbackJpaRepository extends JpaRepository<ScanFeedbackEntity, Long> {

    List<ScanFeedbackEntity> findByScanId(UUID scanId);
}
