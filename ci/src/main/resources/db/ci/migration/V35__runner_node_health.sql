-- A runner's NODE health report (qits-896): the answer to the healthCheck frame qits-ci sends a
-- connected runner — every named check the runner ran on its node (docker, nodeInventory, session,
-- buildkit, network, idRange, stepImage, …) with its own data — or, when the runner did not answer
-- within qits.ci.runner.node-healthcheck.timeout, a report that says NO_ANSWER. A diagnosis only:
-- quarantine, the streak and reinstatement still follow the pseudo-build (last_healthcheck_*) alone,
-- and nothing reads these two columns to decide anything.
--
--   node_health      the newest report, {ok, detail, requestId, dataOmitted, checks:[{name, ok,
--                    detail, data}]}, bounded by RunnerNodeHealth: above its cap every check's data
--                    is dropped and dataOmitted is true, so the verdicts always survive.
--   node_health_at   when that report settled (answered, or timed out).
--
-- V24's shape: nullable, no default, no backfill, part of no constraint and carrying no index. Null
-- is a runner that has never reported, which is every row before this one.
--
-- MigrationChecksumTest pins the SHA-256 of this file. Nothing earlier in the lineage is touched.
alter table ci_runner add column node_health jsonb;
alter table ci_runner add column node_health_at timestamp(6) with time zone;
