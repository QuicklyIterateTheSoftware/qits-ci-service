-- A run says when its lease lost a process to the out-of-memory killer (qits-1818).
--
-- A kernel kill of a step's child used to arrive as a bare exit 137, indistinguishable from a
-- script that exited 137 itself. qits-system now samples each lease container's cgroup
-- memory.events oom_kill and reports it on the lease (TargetLeaseChanged, GET /system/api/leases),
-- so the run records it:
--
--   ci_run.oom_kills   the highest oom_kill count seen for the run's CURRENT lease. Only ever
--                      raised; a fresh lease for a queued run starts it over (recordLease).
--   ci_run.oom_scope   LEASE (the lease hit its own memory limit) or NODE (the node ran out and the
--                      killer picked a process in this lease), as the reading that raised the count
--                      said.
--
-- Both nullable, no default, no backfill: null is "no kill seen", which is every lease on a
-- qits-system that does not report the count yet and every row recorded before this column. V8's
-- shape: part of no constraint, carrying no index, queried into by nothing.
alter table ci_run add column oom_kills bigint;
alter table ci_run add column oom_scope varchar(8);
