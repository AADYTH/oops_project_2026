package com.cropscanner.folix.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * One server-side login session.
 *
 * The browser holds a random 256-bit token in an HttpOnly cookie. Only the SHA-256 HASH of that
 * token is stored here, so a copy of this table (backup, SQL injection, a curious admin) cannot be
 * used to impersonate anyone. Logging out deletes the row, which is what makes logout real
 * (revocation) rather than "the browser forgot the cookie".
 *
 * {@code farmerId} is a plain column rather than a JPA association on purpose: sessions are only
 * ever looked up by token hash, and keeping the mapping flat keeps the per-request lookup to two
 * simple queries.
 */
@Entity
@Table(name = "folix_farmer_session",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_folix_session_token_hash", columnNames = "token_hash")
        },
        indexes = {
                @Index(name = "idx_folix_session_farmer", columnList = "farmer_id"),
                @Index(name = "idx_folix_session_expires", columnList = "expires_at")
        })
public class FolixSessionEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "farmer_id", nullable = false, updatable = false)
    private UUID farmerId;

    /** Lower-case hex SHA-256 of the session token (64 chars). */
    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    protected FolixSessionEntity() {
        // JPA only
    }

    public FolixSessionEntity(UUID id, UUID farmerId, String tokenHash, Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.farmerId = farmerId;
        this.tokenHash = tokenHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public UUID getId() { return id; }
    public UUID getFarmerId() { return farmerId; }
    public String getTokenHash() { return tokenHash; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
