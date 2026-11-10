-- FOLIX Stage 6G — structured remedy guidance on saved reports (PostgreSQL).
--
-- Local/dev (H2 in memory, ddl-auto: update) rebuilds the schema on every start, so you do NOT need this
-- file there. The `prod` profile uses ddl-auto: validate, which never alters tables, so run this ONCE
-- against the production database BEFORE deploying Stage 6G. It is safe to re-run and it does not delete
-- or modify any existing row or table.
--
-- Prerequisite: stage6b-report-ownership.sql (creates folix_scan_report) has already been applied.
--
-- What it does
--   Adds the nullable remedy_plan column backing FolixScanReportEntity.remedyPlan: the organic/chemical
--   treatment guidance (RemedyPlanJsonConverter serializes/deserializes it as a single JSON object).
--   NULL means no matching remedy data was found for the diagnosed disease at save time — that absence is
--   meaningful and is never backfilled or fabricated here. Every report saved before Stage 6G simply has
--   remedy_plan = NULL.
--
-- Column name/type matches com.cropscanner.folix.FolixScanReportEntity (Spring Boot's default
-- camelCase -> snake_case naming: remedyPlan -> remedy_plan).

ALTER TABLE folix_scan_report
    ADD COLUMN IF NOT EXISTS remedy_plan text;

-- Sanity check afterwards:
--   SELECT count(*) FROM folix_scan_report WHERE remedy_plan IS NOT NULL;   -- 0 right after migration
