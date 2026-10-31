-- ci_step.image records the image AS SPELLED from now on (qits-757): `qits/build-images/ci-slot:latest`,
-- with no registry host. Until now qits-ci prefixed a bare `qits/...` reference with
-- a configured artifacts registry host, a key whose shipped default named a host-loopback machine name
-- (`registry.dev.localhost:8080`) — and that string was what every step tile showed. The key is
-- retired; the registry a lease pulls from is the derived public name, added where the lease is
-- asked for (CiSlotAddresses), never written into a row.
--
-- WHY THE OLD ROWS ARE REWRITTEN rather than left as history. The image is a KEY, not a caption:
--
--   * the duration prediction (CiStepRepository) samples history by (repo, config path, step index,
--     image) — "a step that changed its image stops predicting" — so a host on the old rows and none
--     on the new would erase every pipeline's history at the upgrade;
--   * a release run's retry carries over its succeeded steps only when each names the image the
--     source's row recorded (qits-1222), so a release run that failed before the upgrade could not
--     resume after it.
--
-- WHAT IS REWRITTEN: a leading `<host>/` where the host is a registry authority (its first segment
-- carries a '.' or a ':') and what follows is this platform's own namespace, `qits/`. That is exactly
-- the shape the retired prefixing produced; a reference a recipe spelled with its own registry
-- (another namespace) is not touched. Idempotent: a rewritten value no longer matches.
--
-- ci_run.step_images (V21) is NOT rewritten here: its keys are normalised the same way where they
-- are decoded (entity/StepImages), which also covers a run an older instance accepted while this one
-- was starting.
update ci_step
   set image = regexp_replace(image, '^[^/]*[.:][^/]*/', '')
 where image ~ '^[^/]*[.:][^/]*/qits/';
