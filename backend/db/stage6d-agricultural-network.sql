-- FOLIX Stage 6D — Agricultural Network: farmer agricultural profile + support directory (PostgreSQL).
--
-- Local/dev (H2 in memory, ddl-auto: update) rebuilds the schema on every start, so you do NOT need this
-- file there. The `prod` profile uses ddl-auto: validate, which never alters tables, so run this ONCE
-- against the production database BEFORE deploying Stage 6D. It is safe to re-run and it does not delete
-- or rewrite anything (the seed insert only fires when the table is empty).
--
-- Prerequisites: backend/db/stage6a-farmer-auth.sql, stage6b-report-ownership.sql and
-- stage6c-location-disease-map.sql have already been applied.
--
-- What it does
--   1. folix_farmer_agri_profile — one row per farmer who has saved agricultural/farm information
--      (village/area, crops, preferred support category). No row = "not filled in yet". Separate table:
--      folix_farmer is not modified. Same pattern as folix_farmer_location (Stage 6C).
--   2. folix_support_contact — the farmer-facing agricultural support directory. Deliberately separate
--      from authority_contact (the authority/case-management table used by a later stage): this table has
--      no webhook, API key or self-registration concept, and Stage 6D never touches authority_contact.
--
-- Column names/types match com.cropscanner.folix.agrinetwork.FolixFarmerAgriProfileEntity and
-- com.cropscanner.folix.agrinetwork.FolixSupportContactEntity.

CREATE TABLE IF NOT EXISTS folix_farmer_agri_profile (
    farmer_id          uuid                        PRIMARY KEY,
    village_or_area    varchar(150),
    crops               varchar(300),
    preferred_category varchar(40),
    updated_at          timestamp(6) with time zone NOT NULL,
    CONSTRAINT fk_folix_farmer_agri_profile_farmer FOREIGN KEY (farmer_id) REFERENCES folix_farmer (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS folix_support_contact (
    id             bigserial     PRIMARY KEY,
    name            varchar(150)  NOT NULL,
    category        varchar(40)   NOT NULL,
    organization    varchar(150),
    locality        varchar(150),
    phone           varchar(30),
    email           varchar(254),
    address         varchar(300),
    active          boolean       NOT NULL DEFAULT true,
    is_demo_data    boolean       NOT NULL DEFAULT true
);

CREATE INDEX IF NOT EXISTS idx_folix_support_contact_active ON folix_support_contact (active);

-- No seed INSERT here on purpose: the three clearly-labelled DEMO/placeholder directory rows are seeded by
-- com.cropscanner.folix.agrinetwork.FolixSupportContactSeeder at application startup (only when the table
-- is empty), the same CommandLineRunner-on-startup approach AuthorityImportService already uses for its
-- own directory. That keeps ONE place responsible for the seed content instead of two.

-- Sanity checks you can run afterwards:
--   SELECT count(*) FROM folix_farmer_agri_profile;                 -- 0 right after migration
--   SELECT count(*) FROM folix_support_contact WHERE active = true; -- 3 right after a fresh migration
