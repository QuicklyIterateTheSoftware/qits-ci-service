-- A runner that keeps failing is QUARANTINED, and a HEALTH CHECK — a pseudo-build pinned to one
-- runner — is what proves it healthy again (epic qits-440, qits-466).
--
-- WHY A RUNNER NEEDS TAKING OUT OF SERVICE AT ALL. A runner is a machine a person owns, outside the
-- swarm, and when it breaks it breaks for every run it reserves: an image pull its docker cannot do,
-- a step daemon that never dials back through the edge, a socket that drops. Each of those ends a
-- step LAUNCH_FAILED, NEVER_STARTED or CONNECTION_LOST before the build script ever ran — and a
-- runner that stays in rotation keeps reserving the queue's work and turning it red, faster than any
-- healthy host can build it green. So a streak of those outcomes takes the runner out (its effective
-- slots become 0), and only a health check it passes, or a person, puts it back.
--
-- ci_runner, the quarantine:
--
--   quarantined_at / quarantine_reason   set together when the runner is taken out, cleared together
--                                        when it is reinstated. Null is "in service", which is what
--                                        every runner that exists before this migration is: nothing
--                                        is backfilled, because nothing has been observed about any
--                                        of them yet. The row's own `slots` is never touched — it is
--                                        what an operator configured, and it is what a reinstated
--                                        runner gets back.
--   infra_failures                       how many runner-caused step failures in a row this runner
--                                        has had. NOT NULL DEFAULT 0 — V3's lesson about a not-null
--                                        column on a live table: the default writes every existing
--                                        row correctly. It stays, unlike V3's, because 0 is the value
--                                        a newly created row really has and every insert here is by
--                                        JPA, which writes the field anyway.
--   infra_failure_runs                   the DISTINCT runs that streak spans, as the JSON array text
--                                        of their ids — downstream_repos' storage decision (V15), read
--                                        whole and queried into by nothing. The quarantine needs the
--                                        streak to cross at least two runs, because one run whose
--                                        recipe names an image nobody published fails LAUNCH_FAILED on
--                                        every attempt too, and that is the recipe's fault rather than
--                                        the runner's. Null is an empty streak.
--
-- A step that STARTED — its daemon dialled back, whatever the script then did — resets both, which
-- is what makes the count "in a row".
--
-- ci_runner, the newest health check: when it settled, PASSED or FAILED, which run it was, and what
-- it said (the step's outcome and the head of its output). All four null until the runner's first
-- check settles. text for the detail, V1's rule for anything a step produced.
--
-- ci_run.purpose: WHAT A RUN IS FOR. 'BUILD' is every run there has ever been, and the default writes
-- that onto every existing row — the same add-with-default V3 teaches, and here the default STAYS:
-- every insert path that predates health checks writes a build and must keep doing so without
-- learning a new word. 'HEALTHCHECK' is the pseudo-build: a one-step pipeline (`echo hello world`)
-- that exercises exactly the path a runner can break — image pull, daemon download, the dial back
-- through the edge, the clone through githost with the run's credential — and whose verdict is about
-- the RUNNER, never about a commit. So it announces no BuildSuccessful/BuildFailed/
-- BuildStatusChanged, gates no release request, is listed in no repository's runs and counts in no
-- queue forecast; it is readable by id, which is how a runner's page links it. varchar(32) and no
-- check constraint, for V1's reason: the vocabulary is an enum (CiRunPurpose) written by
-- @Enumerated(STRING) and nothing else.
--
-- ci_run.target_runner_id: the one runner a health check may be reserved by — null on every build.
-- No foreign key, runner_id's reason (V23). A partial index, V8's shape, because the two reads that
-- look it up ("does this runner have a check pending", "is there a check for the runner asking")
-- only ever want the rows that carry one.
--
-- MigrationChecksumTest pins the SHA-256 of this file, so editing it after it has shipped is a red
-- build here rather than a refused boot in the deployment. Nothing earlier in the lineage is touched.
alter table ci_runner add column quarantined_at timestamp(6) with time zone;
alter table ci_runner add column quarantine_reason text;
alter table ci_runner add column infra_failures integer not null default 0;
alter table ci_runner add column infra_failure_runs text;
alter table ci_runner add column last_healthcheck_at timestamp(6) with time zone;
alter table ci_runner add column last_healthcheck_result varchar(16);
alter table ci_runner add column last_healthcheck_run_id varchar(255);
alter table ci_runner add column last_healthcheck_detail text;

alter table ci_run add column purpose varchar(32) not null default 'BUILD';
alter table ci_run add column target_runner_id uuid;

create index idx_ci_run_target_runner_id on ci_run (target_runner_id)
    where target_runner_id is not null;
