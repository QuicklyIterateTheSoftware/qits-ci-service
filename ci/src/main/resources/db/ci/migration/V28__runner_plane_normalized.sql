-- The INTERNAL runner plane was deleted in qits-515 (epic qits-444): CiRunnerPlane has only EDGE,
-- and CiRunnerPlaneConverter has read any stored value as EDGE, and written only EDGE, ever since.
-- The column itself was left alone — ci_runner.plane still defaulted to 'INTERNAL' (V23), and
-- whatever rows existed before qits-515 still stored that word. Live carries no such row, but a
-- stored default that disagrees with the only value the application ever writes is worth closing
-- rather than carrying forever behind the converter's tolerance.
--
-- MigrationChecksumTest pins the SHA-256 of this file. Nothing earlier in the lineage is touched.
update ci_runner set plane = 'EDGE' where plane <> 'EDGE';
alter table ci_runner alter column plane set default 'EDGE';
