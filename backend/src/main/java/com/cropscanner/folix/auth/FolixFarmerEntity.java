package com.cropscanner.folix.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * A FOLIX farmer account (Stage 6A).
 *
 * Deliberately minimal: an id, a display name, the login identifier (email), the password HASH,
 * a status and a creation time. No phone number, address, farm location or any other personal
 * data — the Agricultural Network profile is a later stage.
 *
 * The plaintext password is never stored, and there is intentionally no {@code toString()} so the
 * hash cannot end up in a log line by accident. Column names are explicit (not derived) so the
 * production SQL in {@code backend/db/stage6a-farmer-auth.sql} matches exactly.
 */
@Entity
@Table(name = "folix_farmer", uniqueConstraints = {
        @UniqueConstraint(name = "uk_folix_farmer_email", columnNames = "email")
})
public class FolixFarmerEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** Trimmed and lower-cased before it is stored; unique. */
    @Column(name = "email", nullable = false, length = 254)
    private String email;

    /** BCrypt hash (60 chars). Never returned by any API. */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private FolixFarmerStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected FolixFarmerEntity() {
        // JPA only
    }

    public FolixFarmerEntity(UUID id, String name, String email, String passwordHash,
                             FolixFarmerStatus status, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.passwordHash = passwordHash;
        this.status = status;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public FolixFarmerStatus getStatus() { return status; }
    public void setStatus(FolixFarmerStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
}
