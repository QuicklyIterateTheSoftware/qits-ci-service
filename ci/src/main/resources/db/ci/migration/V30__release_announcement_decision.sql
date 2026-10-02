-- publish: if-changed (epic qits-620, task qits-640). The owed row records the entry's publish policy
-- and, once the join closes, what was decided about it at the release version. V29's shape three
-- times: nullable, no default, no backfill, part of no constraint and carrying no index.
--
--   publish          'if-changed', or null = 'always': every row older than this column, and every
--                    entry declaring no policy — which, until release B of qits-640 accepts the key,
--                    is every entry.
--
--   decision         'PUBLISHED' | 'UNCHANGED' | 'ABSENT' | 'UNVERIFIED'; null while the row is owed.
--                    An 'always' row is PUBLISHED when it is announced (the declaration is believed,
--                    as it always was); a checked row is what the store answered. A null decision on
--                    a row that HAS announced_at was settled before this column existed, and reads
--                    as its skip_reason says: none = published, ABSENT, UNVERIFIED.
--
--   unchanged_since  the newest stored version when decision = 'UNCHANGED', else null.
--
-- skip_reason (V29) stays and is still written: it is what tells an announced row from a settled
-- one for every reader that predates this file.
--
-- MigrationChecksumTest pins the SHA-256 of this file. Nothing earlier in the lineage is touched.
alter table ci_release_announcement add column publish varchar(16);
alter table ci_release_announcement add column decision varchar(16);
alter table ci_release_announcement add column unchanged_since varchar(128);
