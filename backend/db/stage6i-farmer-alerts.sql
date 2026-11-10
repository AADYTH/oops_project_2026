-- FOLIX Stage 6I — nearby farmer alerts (PostgreSQL).
--
-- Local/dev (H2 in memory, ddl-auto: update) rebuilds the schema on every start, so you do NOT need this
-- file there. The `prod` profile uses ddl-auto: validate, which never alters tables, so run this ONCE
-- against the production database BEFORE deploying Stage 6I. It is safe to re-run and it does not delete
-- or modify any existing row or table.
--
-- Prerequisites: stage6a-farmer-auth.sql, stage6b-report-ownership.sql, stage6c-location-disease-map.sql,
-- and stage6h-epidemic-risk.sql have already been applied.
--
-- What it does
--   Creates folix_farmer_alert — farmer-to-farmer epidemic alerts.
--   Separate from the authority-side alert system (CropGuard AlertEntity).
--   Recipient-scoped, with a 7-day expiry and 24-hour cooldown per disease.
--   Preserves privacy: does NOT store or expose sender farmer id, scan id, or exact coordinates.
--
-- Column names/types match com.cropscanner.folix.farmeralert.FolixFarmerAlertEntity.

CREATE TABLE IF NOT EXISTS folix_farmer_alert (
    id                   bigserial                   PRIMARY KEY,
    recipient_farmer_id  uuid                        NOT NULL,
    disease_name         varchar(255)                NOT NULL,
    crop_name            varchar(255)                NOT NULL,
    region_lat_mdeg      integer                     NOT NULL,
    region_lng_mdeg      integer                     NOT NULL,
    epidemic_risk        boolean                     NOT NULL DEFAULT true,
    created_at           timestamp(6) with time zone NOT NULL,
    expires_at           timestamp(6) with time zone NOT NULL,
    CONSTRAINT fk_folix_farmer_alert_farmer
        FOREIGN KEY (recipient_farmer_id) REFERENCES folix_farmer (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_folix_farmer_alert_recipient ON folix_farmer_alert (recipient_farmer_id, expires_at);
CREATE INDEX IF NOT EXISTS idx_folix_farmer_alert_cooldown ON folix_farmer_alert (recipient_farmer_id, disease_name, created_at);

-- Sanity check afterwards:
--   SELECT count(*) FROM folix_farmer_alert;   -- 0 right after migration
