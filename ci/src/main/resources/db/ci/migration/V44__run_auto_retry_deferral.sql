-- A run whose automatic retry could not be accepted for a TRANSIENT reason waits for it (qits-2163).
--
-- On 2026-11-12 an edge outage lost every running step, and the automatic retry those infra
-- failures earn (V25, qits-440) could not be made: re-pinning the step images asks the registry
-- through the same dark edge. autoRetry logged that and settled the run FAILED with BuildFailed,
-- so every release request it gated was rejected over an outage. Now a refusal that is the
-- environment's rather than the run's — a registry that did not answer, the database gone for a
-- moment, a service unavailable — leaves the failed run waiting for its retry instead, and a sweep
-- (CiRunService.sweepDeferredRetries) asks again until the deadline written here.
--
--   ci_run.auto_retry_deferred_until    when the wait gives up: the run then settles FAILED and
--                                       BuildFailed is announced, exactly as before. Non-null is the
--                                       whole of "awaiting its automatic retry", which is what the
--                                       sweep reads after a restart.
--   ci_run.auto_retry_deferred_reason   the retry_reason the retry will carry when it is made.
--   ci_run.auto_retry_next_attempt_at   until when one sweeper holds the wait — a LEASE, pushed
--                                       forward by a conditional UPDATE before each attempt, so a
--                                       process that dies mid-attempt strands nothing: the next
--                                       sweep after it takes the run again. Null is "nobody holds
--                                       it, ask at the next tick".
--
-- Cleared together, only once the wait is over: by the retry's own insert (automatic or a
-- person's), or by the sweep when the deadline passes, a refusal is not transient or a newer run of
-- the question exists, and by the release request's cancellation. All nullable, no default, no
-- backfill: no run waited before this, and null says so. V8's shape: part of no constraint,
-- carrying no index — the sweep reads a handful of rows a minute at most, and every one of them is
-- a failed run.
alter table ci_run add column auto_retry_deferred_until timestamp(6) with time zone;
alter table ci_run add column auto_retry_deferred_reason varchar(255);
alter table ci_run add column auto_retry_next_attempt_at timestamp(6) with time zone;
