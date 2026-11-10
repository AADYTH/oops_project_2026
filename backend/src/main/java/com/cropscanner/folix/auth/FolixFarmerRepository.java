package com.cropscanner.folix.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Persistence for {@link FolixFarmerEntity}. Emails are stored normalized, so lookups take a normalized email. */
public interface FolixFarmerRepository extends JpaRepository<FolixFarmerEntity, UUID> {

    Optional<FolixFarmerEntity> findByEmail(String email);

    boolean existsByEmail(String email);
}
