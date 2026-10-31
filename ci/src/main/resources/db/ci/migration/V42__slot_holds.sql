-- A green release-request QA run's CI slot, HELD for its release (epic qits-732, qits-1788).
--
-- A run is one CI lease, one ci-slot container, one qits-ci-daemon. Until now every run closed its
-- lease the moment it settled: Close to the daemon, DELETE /system/api/leases/{id}, the token
-- revoked. A release request's QA run (phase 1) is followed, once the request releases, by the tag
-- build (phase 2, triggered by SCMRelease) — a second lease, a second image pull, a second clone,
-- a second build of the same tree. So a green QA run of a repository whose composition has a
-- release half now keeps its lease open for a bounded window (qits.ci.release-hold.timeout), and
-- phase 2 can continue in that same container.
--
-- ONE ROW PER HELD LEASE, and the row is NOT a run status. qits-projects holds a release request
-- while GET /ci/api/runs/active lists a run of its (repoId, commitSha), so the QA run settles
-- SUCCESS and is announced exactly as before; the hold lives beside the run, in this table, where
-- no listing of runs reads.
--
--   lease_id             the held lease in qits-system (no foreign key: another service's row).
--   repo_id, release_request_id, release_request_qualified_id
--                        which request's release the slot is held for. One hold per request: a
--                        newer one supersedes the older (the unique index below).
--   held_from_run_id     the phase-1 run. It is still the lease's ownerRef at qits-system, for the
--                        lease's whole life, and its ci-run token is the one the container holds.
--   daemon_id            the launch the slot's daemon names in its Hello.
--   target_id            the target the lease runs on, as the run's claim recorded it.
--   daemon_version       the qits-ci-daemon build the container downloaded.
--   merged_sha           the fold the QA run built, what an ApprovalRequirementChanged is matched on.
--   held_at, expires_at  the window; past expires_at the hold is released.
--
-- The types are ci_run's for the same values (V1, V8, V36, V39), so a value one table can hold the
-- other can too. No check constraint, for V10's reason; no foreign key to ci_run, because a hold
-- outliving a pruned run must still be releasable.
--
-- A qits-ci that starts releases every row it finds: the launch a hold needs (the daemon's
-- connection, the token's subject) is in-memory and did not survive the process.
create table ci_slot_hold (
    lease_id uuid not null primary key,
    repo_id varchar(255) not null,
    release_request_id varchar(255) not null,
    release_request_qualified_id varchar(255),
    held_from_run_id varchar(255) not null,
    daemon_id varchar(64) not null,
    target_id uuid,
    daemon_version varchar(64),
    merged_sha varchar(64) not null,
    held_at timestamp(6) with time zone not null,
    expires_at timestamp(6) with time zone not null
);

create unique index ci_slot_hold_rr on ci_slot_hold (repo_id, release_request_id);

-- The phase-2 run that continued in a held slot names the phase-1 run it continued from. Written by
-- the adoption (qits-1789), never by this migration: nullable, no default, no backfill, part of no
-- constraint and carrying no index — V8's shape. Null is every run that started in a lease of its
-- own, which is every run before the adoption exists.
alter table ci_run add column continued_from_run_id varchar(255);
