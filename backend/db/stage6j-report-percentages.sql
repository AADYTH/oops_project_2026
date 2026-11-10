-- FOLIX Stage 6J — store severity % and spread-chance % on saved reports (PostgreSQL).
--
-- Local/dev (H2 in memory, ddl-auto: update) rebuilds the schema on every start, so you do NOT need this
-- file there. The `prod` profile uses ddl-auto: validate, which never alters tables, so run this ONCE
-- against the production database BEFORE deploying Stage 6J. It is safe to re-run and it does not delete
-- or modify any existing row.
--
-- Prerequisite: stage6b-report-ownership.sql (creates folix_scan_report) has already been applied.
--
-- What it does
--   Adds two NULLABLE integer columns backing FolixScanReportEntity.severityPercent / spreadChancePercent
--   (Spring Boot naming: severityPercent -> severity_percent). NULL means "not available" and is what every
--   report saved before this stage keeps — nothing is inferred or backfilled from the severity word.

ALTER TABLE folix_scan_report ADD COLUMN IF NOT EXISTS severity_percent integer;
ALTER TABLE folix_scan_report ADD COLUMN IF NOT EXISTS spread_chance_percent integer;

ALTER TABLE folix_scan_report DROP CONSTRAINT IF EXISTS ck_folix_scan_report_severity_percent;
ALTER TABLE folix_scan_report ADD CONSTRAINT ck_folix_scan_report_severity_percent
    CHECK (severity_percent IS NULL OR severity_percent BETWEEN 0 AND 100);
ALTER TABLE folix_scan_report DROP CONSTRAINT IF EXISTS ck_folix_scan_report_spread_percent;
ALTER TABLE folix_scan_report ADD CONSTRAINT ck_folix_scan_report_spread_percent
    CHECK (spread_chance_percent IS NULL OR spread_chance_percent BETWEEN 0 AND 100);

-- Sanity check afterwards:
--   SELECT count(*) FROM folix_scan_report WHERE severity_percent IS NOT NULL;   -- 0 right after migration
