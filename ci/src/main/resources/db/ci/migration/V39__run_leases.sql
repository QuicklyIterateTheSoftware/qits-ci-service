-- A CI run is one lease on an execution target (epic qits-1481, "CI in a slot", qits-1544).
--
-- qits-ci no longer has runners of its own: a run asks qits-system for a CI slot when it is queued,
-- the lease's one container runs every step, and the lease ends when the run closes. So the runner
-- table and every run column that named a runner go, and the run gains the lease and the target it
-- was granted on:
--
--   ci_run.lease_id    the run's lease in qits-system (no foreign key: another service's row)
--   ci_run.target_id   the target the lease was granted on, written by the claim
--
-- Both nullable, no default, no backfill: a queued run holds no lease until it has asked for one, a
-- lease is granted no target until it runs, and no historical row had either.
--
-- DROPPED, and none of it is history of a run:
--
--   ci_runner                  the runner rows (registration, quarantine, node health) — a target's
--                              state is qits-system's now;
--   ci_run.runner_id           which runner held a run (V23) and its partial index;
--   ci_run.target_runner_id    which runner a health check was for (V24) and its partial index;
--   ci_run.avoid_runner_ids    debt since qits-443 (V27): read and written by nothing.
--
-- What a run that was in flight at the cutover becomes is the boot sweep's, not this file's: a RUNNING
-- event run holds no lease, so it is settled as a run that could not be taken back — a publishing run
-- FAILED, any other queued again and asked a lease for. A runner's health check left QUEUED or RUNNING
-- has nothing left to run it: it is settled CANCELLED/TRIGGER_RETIRED here, in the one statement that
-- can still tell it apart. ci_run.purpose stays: historical rows carry HEALTHCHECK, and the
-- repository listings keep them out by it.
update ci_run
   set status = 'CANCELLED',
       cancellation_reason = 'TRIGGER_RETIRED',
       finished_at = now()
 where purpose = 'HEALTHCHECK'
   and status in ('QUEUED', 'RUNNING');

drop index if exists idx_ci_run_runner_id;
drop index if exists idx_ci_run_target_runner_id;
alter table ci_run drop column runner_id;
alter table ci_run drop column target_runner_id;
alter table ci_run drop column avoid_runner_ids;
drop table ci_runner;

alter table ci_run add column lease_id uuid;
alter table ci_run add column target_id uuid;
create index idx_ci_run_lease_id on ci_run (lease_id) where lease_id is not null;
