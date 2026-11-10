package com.cropscanner.folix.agrinetwork;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Persistence for {@link FolixSupportContactEntity}. */
public interface FolixSupportContactRepository extends JpaRepository<FolixSupportContactEntity, Long> {

    /** Only ever what the farmer-facing directory may show: active rows, by name for a stable order. */
    List<FolixSupportContactEntity> findByActiveTrueOrderByNameAsc();
}
