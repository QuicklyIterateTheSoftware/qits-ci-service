-- The release request's logical id, <repository>-rr-<n>, beside the UUID (qits-1158).
--
-- qits-projects gives every release request a readable id and keeps the UUID as the internal key.
-- The UUID stays the key here too: the cancellation and rerun doors, the supersede rule and the
-- baseline lookup all match on release_request_id, unchanged. The new column is what a person reads.
--
--   ci_run.release_request_qualified_id          verbatim from the triggering event's
--                                                releaseRequestQualifiedId.
--   ci_scm_release.release_request_qualified_id  verbatim from SCMRelease's.
--
-- Nullable, no default, no backfill — V8's and V34's shape and reason: an event published before the
-- field existed, a replay and every historical row carry none, and a reader falls back to the UUID.
alter table ci_run add column release_request_qualified_id varchar(255);
alter table ci_scm_release add column release_request_qualified_id varchar(255);
