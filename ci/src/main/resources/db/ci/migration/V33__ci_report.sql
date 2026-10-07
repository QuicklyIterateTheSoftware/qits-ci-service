-- Release reports (epic qits-754, task qits-983): structured results a step submits about itself —
-- test results, coverage, and every later kind — stored per (run, step, kind).
--
-- The store is kind-agnostic on purpose. `kind` is the report kind's wire name
-- ([a-z][a-z0-9-]{0,63}, checked at the door), `kind_version` its payload schema version, and
-- `payload` and `highlights` are JSON this service stores and serves back verbatim without ever
-- reading a field of: what a kind means lives in the CLI that submits it and in the UI component that
-- draws it. A new kind therefore needs no migration.
--
--   payload            the kind's JSON document, opaque.
--   highlights         a JSON array of at most 10 {severity, text, metric, value, delta}, opaque here.
--   baseline_run_id    the run the submitting CLI compared against, and that run's released
--   baseline_version   version — both null when it had no baseline, which is never an error.
--   payload_bytes      the stored payload's UTF-8 length, so a listing can say how big a report is
--                      without reading it.
--
-- A re-submit of the same (run, step, kind) replaces the row; the unique key is what makes "the"
-- report of a kind on a step one row. No foreign key to ci_run, like every other run-scoped table
-- here: the rows are deleted beside the run's steps (CiRunService.discardRun and the boot sweep's
-- orphan restart) — a report lives and dies with its run and has no retention policy of its own.
--
-- MigrationChecksumTest pins the SHA-256 of this file. Nothing earlier in the lineage is touched.
create table ci_report (
    id uuid not null primary key,
    run_id varchar(255) not null,
    step_index int not null,
    kind varchar(64) not null,
    kind_version int not null,
    payload text not null,
    highlights text not null,
    baseline_run_id varchar(255),
    baseline_version varchar(255),
    payload_bytes int not null,
    submitted_at timestamp(6) with time zone not null
);

alter table ci_report add constraint uq_ci_report_run_step_kind
    unique (run_id, step_index, kind);

-- Every read is by run: the run's reports, one report of the run, a baseline run's reports of a kind.
create index idx_ci_report_run on ci_report (run_id);
