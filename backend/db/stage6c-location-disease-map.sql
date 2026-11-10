-- FOLIX Stage 6C — explicit location sharing + privacy-preserving Disease Map (PostgreSQL).
--
-- Local/dev (H2 in memory, ddl-auto: update) rebuilds the schema on every start, so you do NOT need this file
-- there. The `prod` profile uses ddl-auto: validate, which never alters tables, so run this ONCE against the
-- production database BEFORE deploying Stage 6C. It is safe to re-run and it does not delete or rewrite anything.
--
-- Prerequisites: backend/db/stage6a-farmer-auth.sql and backend/db/stage6b-report-ownership.sql have been applied.
--
-- What it does
--   1. folix_farmer_location — one row per farmer who has EXPLICITLY opted in to sharing a coarse region. No row =
--      "location unavailable" (never asked, denied, or withdrawn). Separate table: folix_farmer is not modified.
--   2. folix_scan_report gains two NULLABLE columns, region_lat_mdeg / region_lng_mdeg. Every existing report gets
--      NULL = "no location". Nothing is back-filled or inferred: Stage 5/6B reports stay valid and stay off the map.
--
-- What is (and is not) stored
--   The values are the CENTRE of a coarse grid cell in integer millidegrees (thousandths of a degree). With the
--   default 100 (= 0.1 degree, about 11 km) every point inside an 11 km square is stored as the same value. There is
--   NO exact latitude/longitude column anywhere: the application discards the browser's coordinates after reducing
--   them to a cell, and never writes them to the database or to logs.
--
-- Column names/types match com.cropscanner.folix.location.FolixFarmerLocationEntity and
-- com.cropscanner.folix.FolixScanReportEntity.

CREATE TABLE IF NOT EXISTS folix_farmer_location (
    farmer_id       uuid                        PRIMARY KEY,
    region_lat_mdeg integer                     NOT NULL,
    region_lng_mdeg integer                     NOT NULL,
    consented_at    timestamp(6) with time zone NOT NULL,
    updated_at      timestamp(6) with time zone NOT NULL,
    CONSTRAINT fk_folix_farmer_location_farmer FOREIGN KEY (farmer_id) REFERENCES folix_farmer (id) ON DELETE CASCADE
);

ALTER TABLE folix_scan_report ADD COLUMN IF NOT EXISTS region_lat_mdeg integer;
ALTER TABLE folix_scan_report ADD COLUMN IF NOT EXISTS region_lng_mdeg integer;

-- Supports the Disease Map query (located reports inside the look-back window).
CREATE INDEX IF NOT EXISTS idx_folix_scan_report_region
    ON folix_scan_report (region_lat_mdeg, region_lng_mdeg, created_at);

-- Sanity checks you can run afterwards:
--   SELECT count(*) FROM folix_scan_report WHERE region_lat_mdeg IS NOT NULL;   -- 0 right after migration
--   SELECT count(*) FROM folix_farmer_location;                                 -- 0 right after migration
