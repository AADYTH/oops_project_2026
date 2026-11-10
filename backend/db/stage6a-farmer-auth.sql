-- FOLIX Stage 6A — farmer accounts and login sessions (PostgreSQL).
--
-- Local/dev (H2, ddl-auto: update) creates these tables automatically; you do NOT need this file there.
-- The `prod` profile uses ddl-auto: validate, which never creates tables, so run this once against the
-- production database BEFORE deploying Stage 6A. Names/types match the JPA mappings in
-- com.cropscanner.folix.auth.FolixFarmerEntity and FolixSessionEntity.

CREATE TABLE IF NOT EXISTS folix_farmer (
    id            uuid                     PRIMARY KEY,
    name          varchar(100)             NOT NULL,
    email         varchar(254)             NOT NULL,   -- stored trimmed + lower-cased
    password_hash varchar(100)             NOT NULL,   -- BCrypt; never the password
    status        varchar(16)              NOT NULL,   -- ACTIVE | DISABLED
    created_at    timestamp(6) with time zone NOT NULL,
    CONSTRAINT uk_folix_farmer_email UNIQUE (email)
);

CREATE TABLE IF NOT EXISTS folix_farmer_session (
    id          uuid                     PRIMARY KEY,
    farmer_id   uuid                     NOT NULL,
    token_hash  varchar(64)              NOT NULL,     -- SHA-256 (hex) of the cookie token; never the token
    created_at  timestamp(6) with time zone NOT NULL,
    expires_at  timestamp(6) with time zone NOT NULL,
    CONSTRAINT uk_folix_session_token_hash UNIQUE (token_hash)
);

CREATE INDEX IF NOT EXISTS idx_folix_session_farmer  ON folix_farmer_session (farmer_id);
CREATE INDEX IF NOT EXISTS idx_folix_session_expires ON folix_farmer_session (expires_at);
