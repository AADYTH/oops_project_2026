-- FOLIX Stage 6H — expose epidemic/spread risk on saved reports (PostgreSQL).
--
-- Local/dev (H2 in memory, ddl-auto: update) rebuilds the schema on every start, so you do NOT need this
-- file there. The `prod` profile uses ddl-auto: validate, which never alters tables, so run this ONCE
-- against the production database BEFORE deploying Stage 6H. It is safe to re-run and it does not delete
-- or modify any existing row or table.
--
-- Prerequisite: stage6b-report-ownership.sql (creates folix_scan_report) has already been applied.
--
-- What it does
--   Adds the not-null epidemic_risk column backing FolixScanReportEntity.epidemicRisk: whether the
--   diagnosed disease itself has a high potential to spread to nearby crops/farms. This is a DIFFERENT
--   concept from severity (how badly THIS plant is affected) and must never be derived from it.
--   Every report saved before Stage 6H defaults to epidemic_risk = false ("not flagged") — never
--   inferred or backfilled from severity/disease name.
--
-- Column name/type matches com.cropscanner.folix.FolixScanReportEntity (Spring Boot's default
-- camelCase -> snake_case naming: epidemicRisk -> epidemic_risk).

ALTER TABLE folix_scan_report
    ADD COLUMN IF NOT EXISTS epidemic_risk boolean NOT NULL DEFAULT false;

-- Sanity check afterwards:
--   SELECT count(*) FROM folix_scan_report WHERE epidemic_risk = true;   -- 0 right after migration
