package com.cropscanner.repository;

import com.cropscanner.entity.ScanRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ScanRecordJpaRepository extends JpaRepository<ScanRecordEntity, UUID> {

    List<ScanRecordEntity> findBySevereTrueAndAnalyzedAtAfter(Instant since);

    List<ScanRecordEntity> findByAnalyzedAtAfter(Instant since);
}
