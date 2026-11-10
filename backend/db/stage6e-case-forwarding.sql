-- FOLIX Stage 6E Part 1 — manual case forwarding (PostgreSQL).
--
-- Local/dev (H2 in memory, ddl-auto: update) rebuilds the schema on every start, so you do NOT need this
-- file there. The `prod` profile uses ddl-auto: validate, which never alters tables, so run this ONCE
-- against the production database BEFORE deploying Stage 6E Part 1. It is safe to re-run and it does not
-- delete or modify any existing row or table.
--
-- Prerequisites: stage6a-farmer-auth.sql, stage6b-report-ownership.sql, stage6c-location-disease-map.sql and
-- stage6d-agricultural-network.sql have already been applied.
--
-- What it does
--   Creates folix_case_forward — one row per saved report that its owning farmer has manually forwarded to
--   an Agricultural Network support contact (folix_support_contact). folix_scan_report is NOT modified.
--   report_id is UNIQUE: a report can be forwarded at most once. There is no farmer column; ownership is
--   always derived through the report. Nothing is created automatically — rows only appear when a farmer
--   explicitly forwards a report.
--
-- Column names/types match com.cropscanner.folix.forwarding.FolixCaseForwardEntity.

CREATE TABLE IF NOT EXISTS folix_case_forward (
    id                 uuid                        PRIMARY KEY,
    report_id          uuid                        NOT NULL,
    support_contact_id bigint                      NOT NULL,
    status             varchar(32)                 NOT NULL,   -- FORWARDED (later stages may add states)
    forwarded_at       timestamp(6) with time zone NOT NULL,
    CONSTRAINT uq_folix_case_forward_report  UNIQUE (report_id),
    CONSTRAINT fk_folix_case_forward_report  FOREIGN KEY (report_id) REFERENCES folix_scan_report (id),
    CONSTRAINT fk_folix_case_forward_contact FOREIGN KEY (support_contact_id) REFERENCES folix_support_contact (id)
);

CREATE INDEX IF NOT EXISTS idx_folix_case_forward_contact ON folix_case_forward (support_contact_id);

-- Sanity check afterwards:
--   SELECT count(*) FROM folix_case_forward;   -- 0 right after migration
