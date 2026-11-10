-- FOLIX Stage 6F — authority alert dashboard (PostgreSQL).
--
-- Local/dev (H2 in memory, ddl-auto: update) rebuilds the schema on every start, so you do NOT need this
-- file there. The `prod` profile uses ddl-auto: validate, which never creates or alters tables, so run this
-- ONCE against the production/docker-compose database BEFORE starting the backend with that profile. It is
-- safe to re-run and it does not delete or modify any existing row or table.
--
-- Prerequisites: stage6a2-authority-contact.sql (creates authority_contact) has already been applied.
--
-- What it does
--   Creates the `alert` table backing com.cropscanner.entity.AlertEntity — one row per severe-scan alert
--   dispatched to a specific authority, so the authority dashboard can list, acknowledge and resolve alerts
--   instead of them existing only as a fire-and-forget webhook/SMS call or a log line.
--
-- Column names/types match com.cropscanner.entity.AlertEntity.

CREATE TABLE IF NOT EXISTS alert (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    scan_id          uuid                        NOT NULL,
    authority_id     bigint                      NOT NULL,
    crop_name        varchar(255)                NOT NULL,
    disease_name     varchar(255)                NOT NULL,
    severity_percent double precision            NOT NULL DEFAULT 0,
    epidemic_risk    boolean                     NOT NULL DEFAULT false,
    latitude         double precision,
    longitude        double precision,
    channel          varchar(16)                 NOT NULL,   -- WEBHOOK, SMS, FAILED
    status           varchar(16)                 NOT NULL DEFAULT 'NOTIFIED', -- NOTIFIED, ACKNOWLEDGED, RESOLVED
    created_at       timestamp(6) with time zone NOT NULL,
    updated_at       timestamp(6) with time zone,
    resolution_note  varchar(1000),
    CONSTRAINT fk_alert_authority FOREIGN KEY (authority_id) REFERENCES authority_contact (id)
);

CREATE INDEX IF NOT EXISTS idx_alert_authority_id ON alert (authority_id);
CREATE INDEX IF NOT EXISTS idx_alert_status ON alert (status);

-- Sanity check afterwards:
--   SELECT count(*) FROM alert;   -- 0 right after migration
