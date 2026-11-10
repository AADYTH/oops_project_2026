-- FOLIX Stage 6A(ii) — authority directory (PostgreSQL).
--
-- Local/dev (H2 in memory, ddl-auto: update) rebuilds the schema on every start, so you do NOT need this
-- file there. The `prod` profile uses ddl-auto: validate, which never creates or alters tables, so run this
-- ONCE against the production/docker-compose database. It is safe to re-run and it does not delete or
-- modify any existing row or table.
--
-- Why this file exists: stage6d-agricultural-network.sql and stage6f-alerts.sql both reference
-- authority_contact (one explicitly documents that it never touches it, the other has a foreign key to it),
-- but no script in this folder actually created it — that gap is what this file closes.
--
-- What it does
--   Creates authority_contact, backing com.cropscanner.entity.AuthorityEntity: one row per Krishi Vigyan
--   Kendra / District Agriculture Extension Office (or equivalent), populated by AuthorityImportService from
--   /data/kvk_directory_seed.csv and geocoded via OpenStreetMap Nominatim, or created via self-registration.
--
-- Column names/types match com.cropscanner.entity.AuthorityEntity.
--
-- Run this BEFORE stage6d-agricultural-network.sql and stage6f-alerts.sql, both of which depend on it.

CREATE TABLE IF NOT EXISTS authority_contact (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name            varchar(255)     NOT NULL,
    district        varchar(255)     NOT NULL,
    state           varchar(255)     NOT NULL,
    raw_address     varchar(500)     NOT NULL,
    latitude        double precision,
    longitude       double precision,
    webhook_url     varchar(500),
    phone_number    varchar(64),
    email           varchar(255),
    geocoded        boolean          NOT NULL DEFAULT false,
    api_key         varchar(255),
    self_registered boolean          NOT NULL DEFAULT false,
    CONSTRAINT uq_authority_contact_api_key UNIQUE (api_key)
);

CREATE INDEX IF NOT EXISTS idx_authority_lat_lon ON authority_contact (latitude, longitude);

-- Sanity check afterwards:
--   SELECT count(*) FROM authority_contact;   -- 0 right after migration, until AuthorityImportService runs
