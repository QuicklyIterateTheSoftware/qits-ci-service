-- The runners: machines that register with qits-ci and pull step work, rather than containers qits-ci
-- asks qits-containers to start (epic qits-440, "qits-ci runners: a pull-based executor").
--
-- WHAT A ROW IS. One runner an operator declared: a name, how many steps it may hold at once, and
-- where it stands in its registration. It is created by POST /ci/api/runners, which commissions a
-- one-use registration token at qits-idp and hands its value back exactly once; the runner presents
-- that token to POST /ci/api/runners/{id}/register, and is answered its own commissioned client.
-- The two halves of that exchange are the two nullable pairs below, and which of them is set is the
-- whole of a runner's registration state:
--
--   registration_token_id / _subject   set at create (and at every rotation), what the register door
--                                      compares the bearer's `sub` to. The VALUE is never stored:
--                                      qits-idp keeps a hash of it and this table keeps none at all.
--   client_id / registered_at          set once, by the register door. A row with a client is
--                                      registered, and a second register is a 409.
--
-- SLOTS 0 IS A STATE, NOT AN ERROR. A runner with no slots holds no work: it is how an operator
-- drains one without deleting it. Default 1, the shape a runner is created in when nobody says.
--
-- PLANE IS DECLARED NOW AND USED LATER. INTERNAL is a runner on qits-net, dialling this service by
-- its wire alias; EDGE is one outside, dialling through the platform edge — the next epic's. The
-- column exists now so that feature is a value rather than a migration. text and NO CHECK
-- CONSTRAINT, for V1's reason: the vocabulary is an enum in CiRunnerPlane, written by
-- @Enumerated(STRING) and nothing else, and there is no check constraint left in this schema.
--
-- CAPABILITIES ARE JSONB, the first column in this lineage that is not text. They are what the
-- runner said about itself when it registered — architecture, docker, labels — and the next epic's
-- scheduler matches work against them, so it is the one value here something will query into.
-- Null until registration.
--
-- ci_run.runner_id: WHICH RUNNER HELD THE RUN, null on every run a runner did not execute — which is
-- every run today, and every row recorded before this migration. NO FOREIGN KEY, deliberately and
-- for ci_run.repo_id's reason: a decommissioned runner leaves its runs behind as history, and a key
-- would either refuse the decommission or cascade the history away. It carries a partial index, the
-- release_request_id shape (V8), because two reads look runs up by it — "may this runner be
-- deleted" (is a RUNNING run on it) and "how many runs does it hold" — and every row without one is
-- a row neither read wants.
--
-- MigrationChecksumTest pins the SHA-256 of this file, so editing it after it has shipped is a red
-- build here rather than a refused boot in the deployment. Nothing earlier in the lineage is touched.
create table ci_runner (
    id uuid not null,
    name varchar(64) not null,
    description varchar(1024),
    slots integer not null default 1,
    plane varchar(32) not null default 'INTERNAL',
    capabilities jsonb,
    registration_token_id varchar(255),
    registration_token_subject varchar(255),
    client_id varchar(255),
    registered_at timestamp(6) with time zone,
    last_seen_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone not null,
    causation_id uuid,
    constraint pk_ci_runner primary key (id),
    constraint uq_ci_runner_name unique (name)
);

alter table ci_run add column runner_id uuid;

create index idx_ci_run_runner_id on ci_run (runner_id) where runner_id is not null;
