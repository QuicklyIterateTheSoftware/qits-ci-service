-- The release request a release came out of, and the commit its tag points at, kept on the fact row
-- so a report baseline can be found for every repository that releases (epic qits-754).
--
-- CiReportBaselines used to reach the request through the version's RELEASE-phase run: the run that
-- checks the tag out carries the request id and the tag's commit. A repository with no deployment —
-- an spa-frontend, an npm-library, a maven-library — has no release recipe and so no such run, and
-- its every QA run was left with no baseline although the release, its request and that request's
-- green QA run all exist. SCMRelease names both values itself; this is where qits-ci keeps them.
--
--   release_request_id   the request the release came out of, verbatim from SCMRelease.
--   commit_sha           what the release's tag points at, verbatim from SCMRelease.
--
-- Nullable, no default, no backfill — V14's shape and its reason: a release published before the
-- fields existed, a replay out of the durable log and every historical row carry none, and the
-- baseline lookup falls back to the release run for exactly those rows.
alter table ci_scm_release add column release_request_id varchar(255);
alter table ci_scm_release add column commit_sha varchar(255);
