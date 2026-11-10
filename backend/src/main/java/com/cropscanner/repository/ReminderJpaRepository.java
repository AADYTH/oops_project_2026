package com.cropscanner.repository;

import com.cropscanner.entity.ReminderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ReminderJpaRepository extends JpaRepository<ReminderEntity, Long> {

    List<ReminderEntity> findByScanId(UUID scanId);

    List<ReminderEntity> findByCompletedFalseAndDueAtBefore(Instant cutoff);
}
