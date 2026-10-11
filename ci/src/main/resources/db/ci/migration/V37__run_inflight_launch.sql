-- The step launch a run has in flight, on the run row (qits-1192).
--
-- The launch table (CiDaemonRegistry) is memory only. A qits-ci restart used to forget every launch,
-- re-queue every RUNNING run and start it again at step 0, which rebuilt and published a second time.
-- With these columns the successor knows which container is still running on the runner and takes
-- it back instead:
--
--   ci_run.inflight_step_index     the step whose container was launched last.
--   ci_run.inflight_daemon_id      the launch id its ci-daemon names in its Hello.
--   ci_run.inflight_token_subject  the sub of the run's ci-run token; the daemon dials back as it.
--   ci_run.inflight_started_at     when the host sent RunStep; null while the step was not started.
--
-- Nullable, no default, no backfill: a run with no launch yet, every finished run and every historical
-- row carry none. None of the values is a secret: the token itself is never stored.
alter table ci_run add column inflight_step_index integer;
alter table ci_run add column inflight_daemon_id varchar(64);
alter table ci_run add column inflight_token_subject varchar(255);
alter table ci_run add column inflight_started_at timestamp(6) with time zone;
