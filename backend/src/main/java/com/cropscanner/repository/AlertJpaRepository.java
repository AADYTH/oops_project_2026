package com.cropscanner.repository;

import com.cropscanner.entity.AlertEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlertJpaRepository extends JpaRepository<AlertEntity, Long> {

    List<AlertEntity> findByAuthorityIdOrderByCreatedAtDesc(Long authorityId);
}
