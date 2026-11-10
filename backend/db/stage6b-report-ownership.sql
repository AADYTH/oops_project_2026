-- FOLIX Stage 6B — saved scan reports belong to a farmer (PostgreSQL).
--
-- Local/dev (H2 in memory, ddl-auto: update) rebuilds the schema on every start, so it has no Stage 5 data
-- and you do NOT need this file there. The `prod` profile uses ddl-auto: validate, which never alters tables,
-- so run this ONCE against the production database BEFORE deploying Stage 6B. It is safe to re-run.
--
-- Prerequisite: backend/db/stage6a-farmer-auth.sql has been applied (this adds a foreign key to folix_farmer).
--
-- What it does
--   1. Creates folix_scan_report if it does not exist yet. (There was never a SQL script for this table, so a
--      database that only ever ran `validate` may not have it. On a fresh table farmer_id is NOT NULL.)
--   2. If the table already exists (Stage 5 data), adds a nullable farmer_id column, the foreign key to
--      folix_farmer and an index. NO EXISTING REPORT IS DELETED OR MODIFIED.
--   3. Sets farmer_id NOT NULL only if there are no ownerless rows — otherwise it leaves the column nullable
--      and prints a NOTICE. The application never depends on the constraint: every read is filtered by
--      farmer_id, so a row with a NULL owner is simply invisible to every farmer.
--
-- Legacy (Stage 5) reports and ownership
--   Stage 5 saved reports with no account attached, so there is NO trustworthy way to tell which farmer
--   they belong to. This script deliberately does not guess: they keep farmer_id = NULL, stay in the table,
--   and are not shown to anyone. Decide what to do with them yourself, for example:
--     -- how many are there?
--     --   SELECT count(*) FROM folix_scan_report WHERE farmer_id IS NULL;
--     -- if (and only if) you KNOW they all belong to one farmer, e.g. a single-tester prototype:
--     --   UPDATE folix_scan_report SET farmer_id = '<that farmer''s folix_farmer.id>' WHERE farmer_id IS NULL;
--     -- or, if they are disposable test data:
--     --   DELETE FROM folix_scan_report WHERE farmer_id IS NULL;
--   then re-run this script so the column becomes NOT NULL. These statements are commented out on purpose.
--
-- Column names/types match com.cropscanner.folix.FolixScanReportEntity (Spring Boot's default
-- camelCase -> snake_case naming).

CREATE TABLE IF NOT EXISTS folix_scan_report (
    id                  uuid                        PRIMARY KEY,
    farmer_id           uuid                        NOT NULL,
    created_at          timestamp(6) with time zone NOT NULL,
    crop                varchar(255)                NOT NULL,
    plant_part          varchar(255)                NOT NULL,
    disease             varchar(255)                NOT NULL,
    severity            varchar(255)                NOT NULL,
    confidence          varchar(255)                NOT NULL,
    symptoms            text,                       -- JSON array of strings
    reasoning           text,
    recommended_actions text,                       -- JSON array of strings
    needs_human_review  boolean                     NOT NULL,
    status              varchar(32)                 NOT NULL,   -- RECEIVED | REVIEWED | ARCHIVED
    CONSTRAINT fk_folix_scan_report_farmer FOREIGN KEY (farmer_id) REFERENCES folix_farmer (id)
);

-- Existing Stage 5 table: add the owner column (nullable, because old rows have no owner).
ALTER TABLE folix_scan_report ADD COLUMN IF NOT EXISTS farmer_id uuid;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_folix_scan_report_farmer') THEN
        ALTER TABLE folix_scan_report
            ADD CONSTRAINT fk_folix_scan_report_farmer FOREIGN KEY (farmer_id) REFERENCES folix_farmer (id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_folix_scan_report_farmer     ON folix_scan_report (farmer_id);
-- These two exist from Stage 5's JPA mapping; created here only so a database built from this script matches.
CREATE INDEX IF NOT EXISTS idx_folix_scan_report_created_at ON folix_scan_report (created_at);
CREATE INDEX IF NOT EXISTS idx_folix_scan_report_needs_review ON folix_scan_report (needs_human_review);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM folix_scan_report WHERE farmer_id IS NULL) THEN
        RAISE NOTICE 'folix_scan_report has ownerless (Stage 5) rows: farmer_id left NULLABLE. They are hidden from all farmers. See the comments at the top of this file.';
    ELSE
        ALTER TABLE folix_scan_report ALTER COLUMN farmer_id SET NOT NULL;
    END IF;
END $$;
