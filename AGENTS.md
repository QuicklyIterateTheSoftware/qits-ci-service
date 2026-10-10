# qits-ci — working notes

Read `README.md` first: it defines the boundary (what arrives over HTTP, what ci fetches for
itself) and the config surface. This file is the working conventions on top of it.

## The two rules that shape everything

**A clone builds against the platform Maven repository** — no monorepo and no prior `mvn install`.
`qits-eventstream:1.0.0` is resolved from local qits-artifacts; `qits-local-up.sh` publishes it before
building this service. `mvn verify` is the gate once that repository is available.

That is why: the poms duplicate versions instead of inheriting them, the suites stand up their own
bare git repos instead of using fixture submodules, the git host is `StubGitHost` — a real HTTP
server in the suite, serving those bares as `<base>/git/<repoId>` — and the one seam that needs real
docker is faked (`FakeCiStepRunner`) rather than skipped.

The Angular client at `service/src/main/webui` remains the sole submodule. Initialise it before an
image build; qits-eventstream is a normal Maven dependency and must not return as a gitlink.

**`service/` compiles to a GraalVM native image**, the same rule qits-gateway and
qits-workspace-daemon carry. `.sdkmanrc` names `25.0.2-graalce`, so `sdk env` gives you a
`native-image` and `./mvnw verify -Dnative` produces `service/target/qits-ci` in about two minutes
with no container involved. Do not read that as a qualification of the clone-alone rule; it is a
second rule of the same kind, and three things follow:

- **A missing GraalVM does not fail the build.** Quarkus logs `Cannot find the native-image ...
  Attempting to fall back to container build` and shells out to docker for a 1.8 GB Mandrel image.
  Green either way, so the fallback is easy to be in without noticing. Grep a native build's log for
  that line before believing it proved anything. (This context used to shell out to `docker` at
  *runtime* too, which made the word useless as a signal in a log; it does not any more — every
  step container is a runner's, started with `docker run` on the runner's host — so `docker` in a
  build log is now about the build. Look for the line anyway.)
- **Every dependency is a decision about what the builder has to be told.** Reflection, dynamic
  proxies, `ServiceLoader`, resources loaded by computed name and JNI/JNA all need registering, and
  when they are missing the failure lands at *runtime, in the binary*, while the JVM suite stays
  green. Prefer what is already in the image — `java.net.http` over a REST client library, which is
  why every client in `service/…/githost` is hand-rolled — and `java.lang.foreign` over JNA. (The older half of this bullet was `ProcessBuilder` over a
  process library, "which is why `CiProcess` shells out rather than links a docker client".
  `CiProcess` is **deleted**: this service spawns no process at all now, so the cheapest process
  library is no process library.) If a native build needs configuration to pass, that configuration
  is part of the change. There is one explicit registration per jar that builds its own
  `ObjectMapper` — `bus/EventWireReflection` (`containers/ContainersWireReflection` went with the
  qits-containers client in qits-506) — and it is worth reading as the worked example of this bullet: the types are
  ordinary records nobody had to think about until a hand-built `ObjectMapper` put them outside
  everything Quarkus scans. See "The event bus".
- **So is every config default the app boots with.** `quarkus.datasource.ci.jdbc.url` carried
  `AUTO_SERVER=TRUE` out of the monorepo; it asks H2 to start its own TCP server, whose classes are
  not in the image, and the binary died at boot on a default no JVM test ever used. It was dropped
  rather than registered. That URL has since gone entirely — **the store is PostgreSQL, reached
  through the platform's generic resource contract** (`${QITS_RESOURCE_DB_URL}` and its two
  siblings), with no default behind it at all, so the whole class of "a default only the packaged
  artifact ever finds out about" is closed rather than fixed. `CiPackagedSurfaceIT` remains the
  guard, and it hands the launched artifact the ENVIRONMENT VARIABLES rather than the datasource
  keys, precisely so that the shipped expressions stay the ones under test.

## Package and module conventions

`eu.wohlben.qits.ci.*`, split across maven modules with disjoint sub-packages so there is no split
package:

- `ci/` — `entity`, `persistence`, `dto`, `mapper`, `control` and `error`. There is no `migration`
  package any more: the one Flyway migration that had to be Java went with the H2 lineage it was
  answering — see "Schema changes". Framework-free in the sense
  that matters: no JAX-RS, no websockets. Entities are Panache; mappers are MapStruct
  `@Mapper(componentModel = "jakarta")`.
- `service/` — `api` (the JAX-RS routes and the `ExceptionMapper`), `bus`, `githost`,
  `daemonhost` (the ci-daemon control socket, the launch registry and the live relay) and
  `runnerhost` (the runner socket, `RunnerStepRunner` — the sole implementation of the step seam —
  and `StepContainerSettings`/`StepWorkloadSpecs`/`StepAddressPlane`, what a step's `Launch` is
  composed from). It
  read "`api` only" until the daemon control plane landed; the transport lives beside the API because
  it needs a web stack, which is the same line that put `api` here rather than in `ci/`, and it is
  where qits-workspaces keeps its own `daemonhost` for the same reason. `ci/` keeps the
  `CiStepRunner` seam and the orchestrator and gains no web dependency — the step runner is in
  `service/` because it *is* the transport. Two more packages of the same kind: `bus`, both ends of
  the event bus (below); and `githost`, the two clients for the git
  host — the pipeline-config reads and the repository listing (both below). Every one of them is an *adapter* for a seam that lives in
  `ci/control`; that is what the package split says — and every `java.net.http` client living here
  rather than in `ci/` is that same rule applied.

  There was a third, `notify`, holding the deploy announcement — one POST per green run to
  qits-platform-deployments' intake, plus the qits-idp credential it carried. It is **gone**, and so
  is its `PdNotifier` port in `ci/control`: what used to be an outbound HTTP call of ci's is an
  ordinary consumption of the deployer's. What it consumes is `SoftwareRelease` — a green build
  stopped being a reason to deploy, so the `/events/build-succeeded` path that POST addressed is gone
  at that end as well. The intake that remains, `/events/software-released`, is the manual/recovery
  door; nothing here calls it, and no key here configures it. `quarkus-oidc-client` left the pom with that package and has since come back
  for a different hop — the token this service presents to qits-githost
  (`githost/IdpGitHostBearer`); it was also presented to qits-containers until qits-506 deleted that
  path. See "Authentication".
- `service/…/idp/` — the qits-idp commissioning adapter: the client, the run-scoped memory of what
  it minted, and the reconciliation that reaps what no run owns. Another *adapter*, and another
  hand-rolled `java.net.http` client for the reason the whole `githost` package is one. See "The
  credential is commissioned per run".
- There **was** a `service/…/containers/` package — the qits-containers client's producer and its
  native-image registration — and it is deleted with the in-process executor (qits-506), as is the
  `qits-containers-client` dependency: qits-ci calls no orchestrator.
  There **was** a `ci-daemon-protocol/` module here, a vendored copy of the daemon repo's. It is
  gone; the contract is the dependency `eu.wohlben.qits:qits-ci-daemon-protocol` now — see "The
  protocol is a dependency" below.
- `service/…/registry/` — one class, `HttpImagePins`: the client that asks the platform's own OCI
  registry which bytes a step image's tag names right now. An *adapter* like `githost` and `idp`
  are, for the `CiStepImagePins` seam in `ci/control`, and another hand-rolled `java.net.http` one
  for the reason the whole `githost` package is. See "A run is fixed to one toolchain".
- `service/…/runnerhost/` — the runner side of the host, beside `daemonhost`: the runner socket
  (`CiRunnerSocket`), its session table (`CiRunnerRegistry`, also the `CiRunnerPresence` and
  `CiBacklogListener` seams), the `Reserve` → `Take` driver (`RunnerReservations`), the step seam for
  a reserved run (`RunnerStepRunner`, the `ci/control/CiRunnerStepRunner` implementation, typed so it
  never competes for `CiStepRunner`), and `RunnerAddresses`, the single composition of what a runner is told — every address a PUBLIC edge
  name, `https://<host>.qits.${QITS_DOMAIN}` as qits-idp's `PlatformDomain` composes its own origin
  (the CI base and so the `wss://` socket, the idp token url, the registry host), each with an
  override, and **no fallback**: with no public domain and no override it throws
  `RunnerAddresses.UnconfiguredException` naming the key (qits-515 deleted the qits-net aliases it
  used to answer). The same domain is what every address a STEP is told is composed from
  (`StepAddressPlane`). The register door answers it,
  and `RunnerInstallScript` renders the generic script `GET /ci/api/runners/install.sh` serves (the
  runner image — the registry host and the version `CiRunnerPins` reads off the pinned protocol jar —
  which the registry's `Upgrade` to a runner of any other version names too) and the
  one-line `installScript` the create and a rotation answer; two compositions would be two chances
  to disagree. The runner rules themselves are
  `ci/control/CiRunners`, the operator verbs and the register door `api/CiRunnerController` — see
  `README.md` under "Runners". The quarantine and the health check are `ci/control/CiRunnerHealth`
  (the rules, the schedule) over `CiRunners`' row writes, and reach a connected runner through the
  `CiRunnerSignals` seam, which `CiRunnerRegistry` implements: every change of what a runner may hold
  is a fresh `Ack` and a `Backlog`, and every `Ack` to an EDGE runner carries `RunnerRegistryMirrors`'
  table. The runner's NODE health report (qits-896) is `runnerhost/CiRunnerNodeHealth` — the
  `healthCheck` frame, its pending request in memory, the `NO_ANSWER` timeout — reached from the
  schedule through `CiRunnerSignals.nodeHealthCheck` and stored by `CiRunners.recordNodeHealth`; it is
  a diagnosis, and nothing in `CiRunnerHealth` reads it.
- `ci-events/` — the event classes qits-ci emits, `eu.wohlben.qits.ci.events`. Under this repo's own
  namespace because it *is* this repo's vocabulary; depends on `eventstream` and nothing else.

The **directories** are `ci/`, `service/` and `ci-events/`; the artifactIds are `qits-ci-domain`,
`qits-ci-service`, `qits-eventstream` and `qits-ci-events`. The first two mismatch deliberately — the extracted git
history is anchored to the directory names, and generic coordinates like `eu.wohlben:ci` would
collide in the shared `~/.m2` that every workspace container mounts. `eventstream/` no longer
mismatches at all: the directory took the artifact's name when the module left, which is the whole
of what "eventsourcing" ever bought and it bought it badly — the module is an event *bus client*,
not an event-sourcing implementation, and nothing here or anywhere else has an event-sourced
aggregate. The old name is kept alive only by `eventsourcing-plan.md`, which is a historical
document and is not renamed.

## The protocol is a dependency, and its version is the daemon binary

`ci-daemon-protocol/` used to be a module here — a byte-identical copy of
[qits-ci-daemon](https://github.com/QuicklyIterateTheSoftware/qits-ci-daemon)'s module of the same
name, same java package, different artifactId — vendored because that module was published to no
registry and the clone-alone rule is not negotiable. **It is gone.** The daemon repo publishes it as
`eu.wohlben.qits:qits-ci-daemon-protocol` and this reactor depends on it, pinned by the root pom
property `qits.ci-daemon-protocol.version`. Its own pom always said this would be the day: the
package was kept identical precisely so the swap would be a pom change with no source edits.

**The version of that jar IS the `qits-ci-daemon` binary version**, because the thing this service
must *speak* and the thing its step containers must *download* are one release. `CiDaemonBinary` is
the constant — `DAEMON_NAME` plus a `VERSION` filtered from the module's own `${project.version}` at
build time — and `CiDaemonPins` is the one reader. That is qits-workspaces' arrangement with
`WorkspaceImage.VERSION` exactly, one repository over, and for the same measured reason.

Three consequences worth keeping straight:

- **A protocol change reaches this service as a version bump, gated here.** Add a message in the
  daemon repo, bump `CiDaemonProtocol.CAPABILITY_VERSION`, release it, let qits-platform-maintenance
  move the pom line, handle the new case in `CiDaemonRegistry`. Slower than editing a vendored copy,
  and that is the point — the host that must understand a frame is the one whose gate now sees the
  change. There is no copy to keep matched and no `diff -r` to run: a published artifact cannot be
  edited in place, so the "small fix on the consumer's side" that drifted the workspace pair once
  (`migration-plan.md` §9 item 19) has no way to happen.
- **Nothing in the daemon repo reaches qits-ci by itself.** Publishing a version makes it available;
  only a bump in this pom makes it used. Leaving the bump to the maintenance train is normal — what
  is not normal is assuming a release over there changed anything about a running run here.
- **Pin at a RELEASED calver, never a snapshot**, the rule "Adding a dependency on another context"
  states for `qits-eventstream` (and stated for `qits-containers-client` while it was one) and for the reason measured on
  2026-08-12: a green build that resolved nothing is not evidence that it could.

## The ci-daemon control plane

`service/…/daemonhost/` is the host half of the arrangement qits-workspaces has with its own daemon:
a step container runs `qits-ci-daemon`, which **dials out** to qits-ci and receives its step as the
reply to its own `Initialized`. qits-ci never dials in, and — the invariant the whole feature rests
on — **no code path here runs repo-controlled code as a host process or through `docker exec`**.
There is no docker vocabulary left at all: container lifecycle is a `Launch` and a `Reap` sent over
the runner socket, and the runner runs `docker run`/`docker rm` on its own host. `StepContainerSettings.BOOTSTRAP` (`runnerhost/`) is a `static final String` with
zero interpolation, and it now travels as its own JSON list element (`entrypoint` `["/bin/sh"]`,
`args` `["-c", BOOTSTRAP]`), so that property holds by construction rather than by inspection of an
argv.

**The fetch in it is retried, not a single attempt.** qits-artifacts serves the daemon binary and
deploys `stop-first`, so it refuses connections for a window on every deploy; one attempt turned
that window into a red release-request verdict on 2026-09-15 (measured then under the since-deleted
`CiDaemonHandshakeIT`). The loop is explicit shell — the platform's step images are Alpine, and busybox `wget`
has neither a retry nor `--tries` — and its literals stay typed into the text, because a Java
constant folded in here would end the zero-interpolation property. Ten attempts twelve seconds
apart is ~108s against a refused connection, which is why
`qits.ci.daemon-register-timeout-seconds` is 180: the in-container budget has to fit inside the
host's deadline, and the two numbers move together. That key now drives only `RunnerStepRunner`'s
register wait; the runner's answer to a `Launch` has its own, `qits.ci.runner.launch-timeout-seconds`.

**The bootstrap is also the only way to hand a step a FILE, and the registry push credential is the
one that uses it.** The wire has no file field — a spec carries images, environment, mounts and
lifetimes — and qits-ci shares no volume with a step container, so a small file can only be a value
the container writes for itself. `BOOTSTRAP`'s last block writes `$DOCKER_CONFIG/config.json` from
`$QITS_CI_REGISTRY_AUTH_CONFIG` when both are set, which keeps zero interpolation intact (the
credential is a variable the shell reads, never a word in the text) and puts the file at
`StepContainerSettings.REGISTRY_AUTH_DIR` under `/tmp` — **outside `/workspace`**, so it is in neither
the clone nor any `docker build` context a step runs from it. The two variables exist only when the
run has **commissioned** a credential (below) **and** the step declared `docker: true`; anything
else sends the environment that always shipped. Note `StepContainerSettingsTest`
asserts `BOOTSTRAP` contains no `docker` — the variable is upper case and a program would not be, so
that assertion still means what it meant.

### The credential is commissioned per run

**A run's credential is one `ci-run` TOKEN, and nothing else** (qits-475; the only kind since
qits-515). `qits.ci.registry-auth.client-id`/`…client-secret` — one static pair, shared by every run
of every repository — went first; the per-run commissioned CLIENT that replaced them, which a step on
qits-net exchanged for bearers at the idp's alias, went with the internal plane. qits-idp's
commissioning API is what mints the token, and `service/…/idp/` is the adapter for it:

- **`IdpCommissioner`** — hand-rolled `java.net.http`, like every other client here.
  `commissionToken` is `POST <quarkus.oidc-client.qits.auth-server-url>/api/tokens` with HTTP Basic
  of **this service's own** oidc client and `{"contextKind":"ci-run","contextId":"<runId>"}`;
  `deleteToken` gives one back (404 is "already gone"); `liveTokens` lists this owner's live ones.
  `commission`/`decommission`/`live` are the same three verbs on `/api/clients`, and they are a
  **runner's** now: the register door commissions a `ci-runner` client, and no run commissions one.
  **The address is derived, never configured** — a second key would be a second thing to keep in
  step with the first, and two idps would mean minting against one and presenting tokens signed by
  the other.
- **`RunCommissions`** — the run-scoped memory, one token per run, populated **lazily at the run's
  first step** and reused by every later one. Every step clones from the authenticated git host, so
  every step needs it; a run is one credential rather than one per step, which is one thing to leak
  instead of N. Not a row: a commission is worth exactly one run, and a run does not survive this
  process.
- **`CommissionReconciler`** — the durable half. On boot (after both existing boot observers, on its
  own `ci-commission-reconcile` thread — the daemon pin ladder's healthcheck lesson, which outlived
  the ladder) and hourly, it lists and deletes every `ci-run` TOKEN whose `contextId` is not a
  `QUEUED`/`RUNNING` run, which this process is not holding right now, and which is older than ten
  minutes. **A listing it could not read reaps nothing**: `liveTokens()` answers an empty `Optional`
  rather than an empty list precisely so the two cannot be confused. The same pass reaps a runner's
  `ci-runner` client and `ci-runner-registration` token against the runner table; the predicate is
  in its class javadoc and `README.md` under "Runners". **It does not look for `ci-run` clients**:
  one a run of an older qits-ci was still holding at the cutover is not reaped here.
- **`RunGitRefs`** — the Git scope the commission states as `gitRefs` (C6 of the superproject's
  `principal-bound-git-refs-plan.md`; the table is in `README.md`). It reads the run's own
  `QITS_EVENT_NAME` and `QITS_EVENT_PAYLOAD` from `LaunchSpec.env`, so no seam changed.
  `RunCommissions` parses them with its own injected `ObjectMapper` and logs the scope at INFO
  (`Run <id> states gitRefs [...]`). **Never read a field of another bean**: an injected
  `@ApplicationScoped` bean is a client proxy, and its fields are null. On 2026-09-13
  `idp.objectMapper` read null, every `MaintenanceBump` run stated `gitRefs []`, and the githost
  refused every bump push. The hand-wired tests could not see it; `RunCommissionsWiringTest` goes
  through the real proxy.

Three rules for that scope:

- **An event kind states nothing until somebody has checked its recipes.** Add it to
  `RunGitRefs.PUSH_NOTHING` only when no recipe on that event pushes: `[]` refuses every push the
  run makes. Not stated means no Git scope, which is the old behaviour. The push inventory of
  2026-09-12 across every `origin/main` of the estate: the only live pushes are the wrapper's
  bump pipeline. No recipe selects `SoftwareRelease`: the hop files (`ci-event-upstream-*.yml`,
  which force-pushed `maintenance/<payload.repository>`) were deleted on 2026-09-02/03, so that
  event is in `PUSH_NOTHING` too. Copies of them under a service's `src/main/webui` or in the
  bootstrap's `.qits-bootstrap-src` are stale working trees, not recipes.
- **The bump scope is the payload's `branch`**, because that is the one ref
  `ci/src/main/resources/platform-pipelines/maintenance-bump.yml` pushes. `ScreenshotBaselines` is
  retired (qits-1007): `screenshot-baselines.yml`, the one ref it pushed
  under `maintenance/baselines/`, and its `RunGitRefs` arm are gone, and the event states nothing
  now — the unknown-event answer. `ReleaseRequestAutomation` is one generic arm: the payload's
  `branch` only under `maintenance/automations/<payload.kind>/`, the one ref a composed automation
  kind pushes, so a new
  kind file needs no change there. If a platform pipeline starts to push another ref, change
  `RunGitRefs` with it.
- **A 400 on a scoped commission means qits-idp refused the list.** A qits-idp without the contract
  ignores `gitRefs` and answers 201, so a 400 never means "older idp". `IdpCommissioner` asks again
  at once with `gitRefs: []` and logs an ERROR naming the run and the idp's reason. It fails closed:
  it never commissions without `gitRefs` once the run states a scope, because that widens the
  credential (C5/C6 of the plan). A 400 to the `[]` form fails the step. A run whose trigger states
  nothing is not affected.

Three decisions worth keeping in front of you:

- **A commission that cannot be made fails the STEP.** `RunnerStepRunner` catches
  `CommissionFailedException` and records `LAUNCH_FAILED` with a message naming the call. Launching
  credential-less would turn an idp blip into a 401 minutes later, inside somebody's build, with
  nothing in the record naming the cause. The retry window is `qits.ci.commission.patience` and its
  classification is `holdThrough`'s — 401, a 5xx and nothing answering are about the moment; a 403
  (a commissioned client may not commission) and a 400 are about the request and stand at once.
- **A qits-ci that commissions nothing launches no step.** With
  `quarkus.oidc-client.qits.client-enabled` off — the shipped posture — `RunCommissions.forRun`
  answers null and `RunnerStepRunner` records `LAUNCH_FAILED` naming that key: the token is the
  step's only credential, so a step without one could download no daemon, clone nothing and be
  admitted by no socket. A suite that launches steps installs `idp/ScriptedRunTokens` over the bean.
- **The token reaches the container in the forms it is spent in**: raw as `$QITS_TOKEN` and as
  `$QITS_MAVEN_AUTH_PSW` (the password half of the pair a repository's own maven settings read), and
  base64 inside the docker document. `RunCommissioningTest` asserts that list and that nothing else
  sent — argv, entrypoint, labels, the container name, the bootstrap — contains it.

**The docker document names the registry's and the mirror's public hosts**
(`registry.qits.<domain>`, `mirror.qits.<domain>`) — `StepAddressPlane.authHosts`, composed from the
domain. The docker client picks a login by registry hostname and buildctl does the same, so a build
that pulls its base image from the mirror and pushes to the registry needs both named, and every
entry is `token:<the run's token>`. `qits.ci.docker-auth-hosts`, `qits.ci.buildkit.registry-host`
and the `qits.ci.buildkit.enabled` kill switch are deleted (qits-515). A step that is not a build
gets no `$DOCKER_CONFIG`; when its image is the platform's, its spec carries a one-entry document
for the runner's pull of that image.

**`DOCKER_BUILDKIT=1` and `BUILDX_NO_DEFAULT_ATTESTATIONS=1` ride along on a `docker: true` step.**
Every step image ships buildx as of qits-build-images-oci 2026.814.110556, so a legacy
build here is a *silent fallback* rather than an image with no choice — and a silent fallback is
what quietly drops a `--secret` mount. The first flag turns that into a loud error; the second keeps a push a single
manifest, because buildx attaches provenance and SBOM attestations by default and the platform
registry expects one manifest per tag.

Four things bite:

- **`@WebSocket(path = "/ci/daemon")` is a literal that does not follow `quarkus.rest.path`**, so it
  carries the `/ci` segment itself — and no machine guard reaches it, which is correct rather than an
  oversight: `MachineAuth` guards only where a handler calls it, and this endpoint calls it nowhere.
  What admits a connection is `@RolesAllowed` at the upgrade and then the launch it names: a step's
  daemon dials through the edge with its run's `ci-run` token, the edge forwards the token's subject
  with the role `qits:ci-run`, the daemon's first frame (`Hello`) names its launch, and
  `CiDaemonRegistry.admitByToken` admits it only when that launch is recorded against that subject
  (`WRONG_RUN` otherwise). There is no per-container secret and no `X-Qits-Ci-Daemon-*` header
  (qits-515). `@RolesAllowed` names `qits:ci-run` alone (qits-516): a `qits:system` dial is refused
  403 at the handshake rather than admitted to an upgrade that could only end in `WRONG_RUN`.
- **The path is a cross-repo contract.** `StepAddressPlane` composes
  `wss://ci.qits.<QITS_DOMAIN>/ci/daemon` as `$QITS_CI_DAEMON_URL` and the daemon dials it verbatim.
  Move the `@WebSocket` literal and `StepAddressPlane.DAEMON_SOCKET_PATH` moves with it
  (`StepAddressPlaneTest` holds the two equal).
- **No untimed wait may enter this package.** A run's driver thread parks here instead of on
  a process, so anything that never returns wedges *all* of CI. That covers three kinds of wait, not
  one: the lifecycle futures (`CiDaemonRegistry.await`), writing a frame (`send`), and closing a
  socket (`closeBounded`). `CiDaemonRegistryTimeoutTest` holds it behaviourally *and* by grepping
  this package's sources.

  **The `…AndAwait` family is banned by shape, and that generalisation was bought.** The grep first
  listed `sendTextAndAwait` and `sendBinaryAndAwait` by name, on the correct reasoning that each is
  `sendText(m).await().indefinitely()` — and then sat green over two live `closeAndAwait` calls,
  which are the identical shape under a name nobody had enumerated. One of them was on the reap
  path, so the step-timeout backstop closed a socket whose peer is *by definition* not answering,
  on the run's own thread, with the container's removal as the next statement: a hang there would have wedged CI
  and orphaned the container from a single cause. The untimed part of these lives inside the
  framework's default method, so it never appears in this package's own source and only the *call*
  is visible to a grep. Write the bounded form —
  `close(…).await().atMost(CLOSE_TIMEOUT)`, `sendText(m).await().atMost(SEND_TIMEOUT)` — and note
  that a close gets a much shorter deadline than a send, because a send is delivering something the
  run needs while a close is being polite to a peer that is about to be `rm -f`'d anyway.
  The pattern's own coverage is asserted against known strings in the same test: a guard that can be
  silently incomplete is worth exactly what its coverage is, and that coverage used to be unasserted.
- **This process holds no docker socket, spawns no process and calls no orchestrator** (qits-506).
  Every step container is the runner's: `RunnerStepRunner` sends the runner holding the run a
  `Launch{workloadSpec}` (composed by `runnerhost/StepWorkloadSpecs` from what
  `runnerhost/StepContainerSettings` holds), the runner runs `docker run` on its own host and answers
  `Launched` or `LaunchFailed` with docker's words; `Reap` is answered by `docker rm` and `Reaped`
  carrying the container's log tail; `Released` frees the run's slot. A launch the runner refuses, or
  does not answer inside `qits.ci.runner.launch-timeout-seconds`, is one recorded `LAUNCH_FAILED`.
  What a previous life of the runner left behind is removed by the runner's own boot sweep — qits-ci
  reaps nothing at boot, so the ordered reap-then-sweep pair of `StartupEvent` observers is gone and
  `sweepInterrupted` is the only one. The real `docker run` is tested in qits-ci-runner-daemon, not
  here.

  **What went with it.** `CiDaemonStepRunner`, the `containers/` package, the
  `qits-containers-client` dependency, and `CiDaemonLauncher`'s whole lifecycle vocabulary —
  `launch`, `reap`, `destroyWithLogs`, the owner-scoped boot reap `destroyAllOwned`, the ensure
  retry window (`qits.ci.containers.launch-patience`), the boot-reap patience
  (`qits.ci.containers.boot-reap-patience`) and the owner key (`qits.ci.containers.owner`). What
  survived was renamed `runnerhost/StepContainerSettings`: `BOOTSTRAP` and its paths, the config a
  step spec is composed from, `workloadSettings()`, `imageSpellings()`/`plane()`, `daemonVersion()` /
  `resolveBinaryUrl()` and `containerName()` (`internalPlane()`, `cloneUrl()` and
  `resolvedArtifactsUrl()` went with the internal plane, qits-515). Do not
  reintroduce a docker or orchestrator call here to "help" a runner: the runner owns its host.

- **A teardown that needs the log still gets it ON the removal.** `Reaped` carries the tail the runner
  read before its `docker rm`, which is what keeps the ordering unloseable. The tail is bounded
  **again on this side** against `qits.ci.output-max-chars`: this is the last untrusted boundary
  before the text becomes a row, and a bound only the sender applies is a bound a buggy or hostile
  sender does not apply.

This is the execution path, not a plan for one. `RunnerStepRunner` (behind `CiRunnerStepRunner`) is
the only implementation of `CiStepRunner` — `stepRunnerFor(run)` always answers it — and the
approach the daemon replaced — one `docker run` of a composite `bash -c` with a
clone/checkout prelude and a `PRELUDE_FAILED_MARKER` sentinel — was **eradicated**, not retired. It
does not exist in any form, there is no config toggle selecting it, and no fake performs its
semantics. If a `bash -c` of repository content ever reappears here, host-side or in a docker argv,
that is the regression, not a refactor.

Two more things live in this package and belong to it rather than to `ci/`:

- **`CiStepRelay`** is both halves of one bound. It is the live surface (`GET /ci/api/runs/{runId}`'s
  `live` object, polled — there is no SSE and no WebSocket) *and* the accumulator the persisted tail
  is read back out of at the step's end. One buffer, one budget: the bound is a security property
  and two implementations of it drift into one that is not applied.
  <br>It holds **one** instant beside the text and no more: `started(runId, at)`, stamped where
  `listener.onStarted()` is stamped — the hand-over — so a poll can say how far into the live step a
  run is rather than only that it is running. Null until then, which is the setup window (the
  container has still to be asked for, started and dialled back) and a real state rather than a gap.
  Host-stamped like every other timestamp here, and the relay is deliberately not where a second
  timeline starts to accumulate: what a run really took is `ci_step`'s two columns.
- **Cancellation** is a flag plus a `Cancel` frame. A cancelled step still *finishes* — the daemon
  answers with a terminal frame — so the driver's await completes normally and cancelledness is read
  from `CiRunService`'s own flag, never inferred from how the call came back. Between the launch and
  the first frame there is no step to cancel, so the launch is torn down instead, which completes the
  same await at once.

  **Cancelling a run that has not started at all never reaches this package**, and that is the shape
  a `QUEUED` row bought. `CiRunService.cancel` finds the row still queued, writes it `CANCELLED` in its
  own transaction, and a runner's reservation — `reserveFor`'s conditional UPDATE — then finds a row
  that is no longer `QUEUED` and does not take it: no container, no `Cancel` frame, no launch to tear
  down. The flag is still raised, and that is not belt-and-braces for its own sake: cancel runs on the
  request thread and the reservation on the runner socket's, so if a reservation won the race and
  turned the row `RUNNING` in between, the flag is what stops the run before its first container.
  Neither thread has to win for the answer to be right.

  **A `RUNNING` run this process does not own is settled by `CiRunService.cancel` and never reaches
  this package either.** `CiStepRunner.owns` is what tells the two apart — `RunnerStepRunner`
  answers from the runner registry's holds (a driver holds a run from its `Take` to its `Released`) —
  because `cancel(runId)` is a no-op and a cancellation coming back the same way. Such a row is what a
  dead predecessor leaves, and a `RUNNING` row with no `runner_id` is history from the in-process
  executor: no seam owns it, so asking one to stop it asks nothing of nobody and nothing would ever
  write the terminal row. Cancel fails the incomplete steps and finishes the run `CANCELLED` itself,
  in one write.

## Reading a repository's pipeline config

`service/…/githost/HttpGitConfigSource` is the only implementation of `CiConfigSource`, and it is
two `GET`s against the git host:

    <qits.ci.git-host-url>/git/<projectId>/<repoName>/blob/<rev>/<path>    the bytes
    <qits.ci.git-host-url>/git/<projectId>/<repoName>/tree/<rev>[/<path>]  {"entries":[…]}

**The repository arrives as a `CiRepoRef`, and `repoUrl` is the one place the two addressing schemes
are chosen between.** A reference carrying `(projectId, name)` is read name-addressed, which is the
public scheme and the only one the git host keeps serving above the projects↔githost seam; one
carrying only a storage id is read `…/git/<repoId>/…`, exactly as this service always read. The id
arm is not legacy tolerance for its own sake — an id-addressed push announces no name at all, and on
a pre-cutover platform the id IS the name — so it is correct where it fires and silent everywhere
else. `CiIdentifiers.requireRepo` validates the name half **only when it is present**; widening that
to "always" would refuse every mirror sync.

Both answer the commit they resolved in a `Git-Commit-Sha` header, and `<rev>` is a sha **or** a ref
name. Four things follow, and each of them replaced something:

- **The push path reads at the pushed sha.** No branch, no mirror, no race with a second push. What
  went with it is the whole contended-fetch machinery — a bare mirror per repository under a data
  dir, `git init --bare`, a fetch into a ci-private ref two workers could lose a CAS on, the bounded
  retry, `ConfigLookup.CONTENDED` and `CiRunService`'s requeue. All of it existed because the wire
  protocol has no blob-at-path verb, so reading one file meant cloning first. Do not reintroduce any
  of it: the host serves any commit it holds, reachable from a ref or not.
- **A 404 on the blob still means "this commit declares no pipeline"**, which is the opt-in case and
  discards the row. It is told apart from a commit the repository does not hold by one more read —
  the tree at that same sha — because those two mean opposite things to the record. So `GONE`
  narrowed on purpose: it is *held*, not *reachable*, and a commit the branch has moved past now
  builds instead of being discarded.
- **The event path lists `.config/qits/` at the branch, takes the head off the header, and reads each
  file at that sha.** The listing and the reads are one commit even if a push lands in between; a run
  must never be recorded against one commit with a trigger file from another. A 404 on the directory
  costs one more read (the root tree) to tell "declares nothing" from "could not ask".
- **`ci/` stays free of `java.net.http`.** The port is `CiConfigSource` in `ci/control`, the client
  is in `service/`, exactly as `GitHostRepoListing` and `CiRepositoryListing` are. That split is also why the
  logging differs by path: the push path WARNs (a repository and a branch existed a moment ago),
  the trigger listing stays at DEBUG (it asks every known repository on every frame, and a deleted
  one is simply not a candidate — a warning per green build forever is how a log stops being read).

The host's side of the contract, including the 8 MiB blob cap and why a slashy branch is written
`feature%2Fx`, is qits-artifacts' `README.md` under "Reading one file without cloning". **qits-ci
spawns no `git`**, and the image no longer carries one.

**Both reads carry `IdpGitHostBearer`'s token, and a missing one costs the HEADER rather than the
call.** The bearer is the `qits` named oidc client (`quarkus.oidc-client.qits`), the one client
every outbound identity this service has (epic qits-540 dossier, 'Plan (as of 2026-09-13)', C4) — it
used to be its own `githost`-named client, audience-bound to qits-githost specifically, before every
outbound call moved to one audience, `qits-platform`. When it has nothing to give — the client is
disabled, or the idp did not answer — the request goes out bare and the git host refuses it, which
is a 401 this class reports like any other status. It used to throw instead, and that was worse in
both directions: with the client off every config read of every run failed before a socket was
opened, and the refusal it stood in for is one the host makes anyway. Same rule, and the same
reasoning, as the qits-containers client had while qits-ci still had one.

## The run queue, and what a run row means

**A run is a row from the moment it is accepted.** `CiRunService.onEventTrigger` `INSERT`s a
`QUEUED` row before it returns and announces the backlog to the connected runners; a runner's
`Reserve` reaches `reserveFor`, whose one conditional UPDATE (`claimQueuedForRunner`: `status = RUNNING,
runner_id = <the runner>` *where* the row is still `QUEUED`) is the whole claim, so a run that was
cancelled while it waited is never picked up. The runner's driver thread then runs it through
`executeReserved`. Everything from there down is unchanged.

**There is ONE entry, and that is the 2026-09-05 change.** `onPostReceive` was the other: one
accepted run per pushed branch ref, reading `.config/qits/ci-post-receive.yml` out of the pushed
commit on this class's own worker. The platform runs no CI outside release requests, so every
repository's file had been deleted on 2026-09-04 — and the engine arm outlived them, enqueueing a run
per push against a file none of them carried (thirteen phantom `QUEUED` rows, measured). **An
ordinary push triggers nothing now, and it does so because there is no code left that could**: the
listener, the accept, the worker half, the config-at-a-sha read and `CiConfigParser` are all gone.
`SCMPublishCommit` still reaches `CiEventTriggerListener` like every other event, so a repository
that declares `event: SCMPublishCommit` is served by the ordinary grammar — a capability that stays,
and one nothing declares today.

**This revised the recording rule on purpose, and the old wording is worth having in front of you.**
It read: *a run is only ever recorded when it says something true about a commit* — which was a
statement about when the `INSERT` happens, and it is what made the queue invisible. It now reads:

> **A run row exists from the moment the work is accepted, and it is removed again if it turns out to
> describe nothing that happened.**

The one case that records nothing still records nothing — a commit force-pushed away, discovered in
a step container's own checkout and confirmed by `CiConfigSource.commitHeld` — and it reaches that by
**discarding** a row that already exists rather than by never writing one (`discardRun`). The
green/red outcomes finish the accepted row instead of inserting a second one. The only observable
difference from the old rule is a transient `QUEUED` row in between, which `GET /ci/api/runs/active`
and, briefly, a repository's own listing will show.

**Two outcomes left this list with the push path and are worth knowing were once here**, because both
are still reachable states on a historical row. A repository that declared no pipeline had its row
discarded (opt-in: it must not accumulate a row per push), and a git host that could not be reached
had it discarded too — **a read failure must not invent a gate**, and a red row is exactly an invented
gate. Both were decided on the executing thread, after the row existed. A trigger file is read and parsed by
`CiEventTriggerService` *before* any row exists now, so a missing or broken one is a WARN and no run,
and nothing writes `CONFIG_ERROR` any more. The rule those cases stated is unchanged and still
load-bearing one seam over: an unreadable candidate is skipped, never a run.

**The `commitHeld` probe carries the same rule in its third answer.** `HELD`/`GONE`/`UNKNOWN`, and
`UNKNOWN` must never be collapsed into `GONE`: a host that could not be asked has said nothing about
the commit, and discarding a run over that would erase a verdict on evidence nobody has.

**`QUEUED` survives a restart.** `sweepInterrupted` leaves those rows queued and announces the
backlog once, so a redeploy landing between acceptance and execution no longer eats a build. A
`RUNNING` event row — a runner's, or one with no `runner_id` left by the in-process executor — is
reset to `QUEUED` and re-run from its snapshot by whichever runner reserves it next (naming itself);
event-trigger scripts are therefore an at-least-once boundary and must be idempotent. Nothing here
adds durability beyond the row.

**What a predecessor's leftovers get is decided by the sweep and the reservation, and it is not
"nothing".** A `POST_RECEIVE` row is work this engine has nothing to run with: a `RUNNING` one is
marked `FAILED` by the boot sweep (its step died with its process, and arbitrary push work is not safe
to replay even if something could), and a `QUEUED` one — like an event row whose trigger snapshot is
unreadable — is settled `CANCELLED` by the first reservation that walks past it, after that
reservation's own transaction, rather than left queued — a row nothing will ever
run sits in `GET /ci/api/runs/active` forever, which is exactly the phantom the retirement is about.
One INFO line says which and why, and the row says it too: `cancellation_reason` is
`TRIGGER_RETIRED` (`TRIGGER_UNREADABLE` for the unreadable snapshot), its own value beside
`USER_CANCELLED`/`DEDUPED`/`RELEASE_REQUEST_CANCELLED`/`SUPERSEDED_BY_RELEASE_REQUEST`/`TRIGGER_UNREADABLE`,
because nobody cancelled it — the engine that would have run it is gone, and somebody reading the row
a year from now should find that out from the row rather than from a changelog.

**A dying process claims nothing, and the deployment is stop-first so it does not have to race.**
`CiRunService.draining` is raised by a `ShutdownEvent` observer — which Quarkus fires before it
destroys beans, so the flag is up before anything is torn down — and both `announceQueued` and
`reserveFor` read it: a draining process neither announces a backlog nor hands out a reservation. A
queued row is then left `QUEUED` for the successor's boot sweep rather than flipped. **Measured 2026-08-23**: qits-ci deployed start-first, the successor
booted and swept, and only *then* did the predecessor claim a row and die holding it `RUNNING` —
past every sweep, executed by nobody, unsettleable through the API. `.config/qits/deployments.yml`
now says `update_order: stop-first` for the same incident; the flag is what makes the shutdown
itself correct, the key is what keeps two processes from overlapping at all.

**The row is still the recovery, and durable consumption did not change that.** The reason used to be
that the bus is at-most-once and could not redeliver; it can now (see "The event bus"), and the row
is still what recovers a `RUNNING` event run — because the event has already been *claimed* by the
time a run is running, so the sweep will not offer it again. The two mechanisms cover different
windows and are stacked, not alternatives: the claim covers the arrival, the row covers the
execution.

An event run stores the original timestamp, canonical payload and exact trigger-file content on its
row. Recovery reparses that immutable snapshot: it neither reads a moved branch nor asks the event
log for anything.

**`onStart` skips test mode, so `sweepInterrupted` is package-private and the suite drives it.** A
claim about a restart is made by seeding the rows a dead process would have left and calling it —
`CiQueuedRunTest` does exactly that, including the ordering. The doctrine there used to be *a
restart must not reorder a backlog*, because the worker was FIFO and `createdAt` was what FIFO had
meant before the process died. **It now reads: a restart re-derives the same suggested order** — the
same rows through the same pure function in whichever process is running — which is a stronger
statement, since it holds for a backlog nothing accepted in this process at all. A queue whose rows
state nothing derives `(createdAt, id)`, so the old case is the new one with no signals in it, and it
is still green unchanged.

### A run is fixed to one toolchain, and the fixing happens once

**Every recipe step on the estate names `qits/build-images/*:latest`, and that is correct.** A
version named only in a recipe gets no keep from qits-platform-maintenance's pins API, so docker GC
would eventually evict the image the whole estate boots from — and twenty repositories pinning by
hand is twenty commits per toolchain release. The tag is the right thing for a repository to write.

**What was wrong is that the run resolved it more than once.** `CiReleaseComposer` emits `image:`
verbatim and `CiRunService` carried it to each container start, so every step asked the registry for
`:latest` at whatever minute it happened to start. A publish from qits-build-images-oci landing
mid-build is then a run that verified against one toolchain and published from another, with nothing
anywhere recording which — the inconsistency this closes (owner ruling, 2026-09-22).

**So the RUN fixes what the recipe floats.** `CiRunService.pinStepImages` runs at **accept**, beside
`predictedStepDurations` and for its placement reason (network I/O inside the insert's bracket would
poison the session and roll the accepted run back). It walks the pipeline's steps, asks
`CiStepImagePins` about each **distinct** reference — the memo is the contract, not an optimisation
— and the answers go onto `ci_run.step_images` (V21). `runSteps` decodes that once and every step is
launched with `pinnedImage(...)`. Two steps naming one tag get one digest by construction.

- **The mechanism is the registry's own.** `HEAD <artifacts>/v2/<name>/manifests/<tag>` and the
  `Docker-Content-Digest` header the OCI distribution spec mandates; qits-artifacts serves that spec
  at the literal `/v2` of its own root and conformance-tests it, and its `PublishGuard` guards the
  six publish surfaces while letting every READ through — so no credential is presented, and one
  would be a credential offered where the store asks for none.
- **The address is derived, never configured**, `IdpCommissioner`'s rule: the origin of
  `qits.artifacts.maven.registry-url` (or `qits.artifacts.url` when a deployment sets it) — where
  qits-ci itself reaches the store from inside the swarm. The *pinned reference* is built with
  `qits.artifacts.registry-host`, and `StepAddressPlane` moves that host to the registry's public
  name for the runner's pull. Resolving through one address and pulling through another is sound
  rather than sloppy: a digest is content-addressed.
- **Four answers, and `UNRESOLVED` must never be collapsed into `FOREIGN`** — `commitHeld`'s
  `UNKNOWN`/`GONE` rule one seam over. Pinned; already pinned by the author (no registry is asked,
  because re-resolving a deliberate pin is this defect wearing the fix's name); foreign, which is
  every image this platform does not publish and which is launched as named with **no** pin recorded,
  since qits-ci holds no credential for another store and "this floated" is worth saying out loud;
  and unresolvable, which is **no run**.
- **Unresolvable refuses the accept and leaves the event OWED.** Nothing was learned about which tool
  the build would use, so it is the unreadable-`release.yml` case exactly: `CiRunService.StepImageUnpinned`
  out of `onEventTrigger`, caught in `CiEventTriggerService.evaluateTrigger`, the candidate onto
  `repositoriesUnreadable`, and a sweep asks a registry that has probably come back. A blip delays a
  run rather than floating it, and a release request is never left waiting on a verdict nothing
  would record again.
- **`ci_step.image` keeps the TAG and that is deliberate.** It is what
  `predictedStepDurations` samples history by — "a step that changed its image stops predicting" —
  and a digest there would make every build-image publish erase every pipeline's history, which is
  not what that rule means. The step row is the reference, the run row is the bytes, and neither says
  what ran alone.
- **A retry re-pins rather than copying**, the prediction's arm rather than `priority`'s: the pin
  says which bytes this execution will use, and a retry that inherited a months-old digest could
  never be healed by fixing a build image — which is the loop `qits ci retry` exists to close.
- `qits.ci.resolve-platform-step-images=false` turns the whole thing off, digest and registry prefix
  together. No second key: that switch's argument (shipped ON, one variable reverses it) already
  covers exactly this blast radius, and a second one would be a second thing to be wrong about.

### The queue is the table, and runners claim out of it

**There is no in-memory queue, and since qits-506 no in-process claimant either.** `enqueue` once
submitted one closure per run to a worker pool, which fixed the order at accept time; its successor
(2026-09-07) was a pool of `qits.ci.concurrent-builds` **claim loops** parked on a wake semaphore for
`qits.ci.queue-poll-interval`. Both are deleted with the in-process executor — `initializeWorkers`,
`supervised`, `workerLoop`, `claimLoop`, `claimAndRunOne`, the pool, the semaphore, `workerCensus`,
`startQueued`, `executeEventRun` and both config keys. **Every run is a runner's now**, the platform
host's included (the `localhost` runner). An accept, a retry and the boot sweep only *announce* the
backlog (`announceQueued` → `announceBacklog` → a `Backlog` frame to each connected runner); a runner
answers with `Reserve`, and `CiRunService.reserveFor(runner)` does exactly this: read every `QUEUED`
row in one transaction (`listQueuedOldestFirst`, kept as the *candidate feed*), hand them to
`CiRunOrdering.suggestedOrder`, and walk the answer taking the first row this runner can have.

**`claimQueuedForRunner` is the whole claim.** One conditional UPDATE — `RUNNING`, `startedAt` and
`runner_id` *where* the row is still `QUEUED` — so a cancelled row is never picked up and two runners
racing for one row are settled by the database. The reservation also refuses outright while this
process is draining. What a failed claim costs is the next candidate, not a parked thread. The
runner's driver thread then runs the reservation through `executeReserved` → `executeClaimed`.

**`CiRunOrdering` is a pure function and that is load-bearing** — no I/O, no clock, no CDI — because
it is what makes the restart doctrine above true and what makes the ordering testable without a
database. Its precedence is kind (an `SCMRelease`-triggered run before everything else, two tiers and
not three), then dependency topology (Kahn's algorithm over `ci_run.downstream_repos`), then priority
(a private rank table), then `(createdAt, id)`. **Priority never outranks topology by construction**:
priority only chooses among in-degree-zero nodes, so an upstream `LOW` necessarily precedes a
downstream `BLOCKING`. A cycle degrades to the next criteria rather than throwing, since this runs
inside a reservation and a fact about the estate must not cost every queued run its claim.

**The rank table is a local copy of another context's vocabulary, and the tension is deliberate.**
Nothing else here interprets the word: both priority columns are plain `varchar`, no check
constraint enumerates the values, and `SoftwareRelease` carries whatever arrived. But there is no
ordering without a rank. What makes it benign is the failure mode — an unknown word, a new word, a
word in the wrong case all rank `MEDIUM` and the run is claimed like any other, so a stale table
costs one misordered queue and never one lost build. Absent ranks `MEDIUM` too, and *not* `LOWEST`:
otherwise the rollout order of qits-projects would decide the build order of every repository whose
events predate the field.

**Two limits are accepted rather than overlooked.** Topology sequences `QUEUED` claims only — a
downstream run may start while its upstream is `RUNNING`, because strict chaining idles slots
behind a long build and stalls outright on one that never ends. And priority starves: a steady
stream of `BLOCKING` work can hold a `LOWEST` run indefinitely, with a manual-reorder API as the
intended escape hatch rather than an ageing fudge nobody can predict.

**One poison row costs one row.** A `QUEUED` row whose `trigger_config` will not parse, or that is
not an event run at all, is passed over inside the reservation's transaction and settled `CANCELLED`
(`TRIGGER_UNREADABLE` / `TRIGGER_RETIRED`) after it commits, each in its own write that re-reads the
status — so it neither holds up the rows behind it nor survives to the next boot. **No candidate
ends the walk except the one that is claimed**, and a row the settling write could not reach is
simply left for the next reservation. (The claim loop's first cut abandoned the scan at such a row
and held up everything behind it; that was fixed 2026-09-08, and the rule moved to the reservation
with the loop's deletion.)

**The suites have no in-process executor to wait on, so they play a runner.** The `ci` module's
`SuiteRunner` is a `CiBacklogListener` that reserves for a one-slot suite runner row and executes via
`executeReserved`, holding runs in `FakeCiStepRunner` (which implements `CiRunnerStepRunner` and
absorbed `FakeRunnerStepRunner`); `CiTestSupport.executePipeline` is `onEventTrigger` plus
`suiteRunner.awaitIdle()`, which returns once no driver is running anything and a reservation just
came back empty — also true of a queue holding only rows this runner may not take, exactly as a real
runner would leave them. The service module has its own `SuiteRunner`, **off by default** and
`enable()`d/`disable()`d per suite; its `FakeCiStepRunner` is a `@Mock CiRunnerStepRunner` that
scripts the runs the SuiteRunner holds and delegates everything else to the real `RunnerStepRunner`.

### A reserved run is settled whatever happens on it

**The in-process claim loop could END, and that failure is why this section exists.** Measured
2026-09-07: after a redeploy, runs sat `QUEUED` indefinitely while every health check stayed green,
because the pool's loops had died one at a time. The loop is gone, and with it the cases that were
only about its thread (a leaked interrupt flag, the supervisor, the census). What still decides
whether the queue moves:

- **An `Error` settles the claimed run, then is rethrown.** `service/` compiles to a native image, so
  `NoClassDefFoundError` and `ExceptionInInitializerError` are the ordinary shape of a missing
  reflection registration. The run is over either way and its row must say so — which is also what
  frees the runner's slot for its next run — while what to do about the JVM is the driver's business.
- **Everything a claimed run does is inside `executeClaimed`'s try.** `runner.pinDaemon()` and
  `pinDaemonVersion` sit inside it: the claim has already flipped the row `RUNNING` by the time they
  run, and a throw out of either used to leave a row `RUNNING` with no steps, no `finishedAt` and no
  owner — the 2026-08-23 stranded-row shape arrived at from the other direction. `pinDaemon()` reads
  a classpath constant and can no longer throw; the placement stays anyway, because
  `pinDaemonVersion` is still a write, and moving a statement out of a try because it has stopped
  being able to fail is how the next statement added beside it ends up outside the bracket.
- **A poison `QUEUED` row is settled by the reservation that walks past it** (above).
- **The count is a surface, and never a gate.** `api/CiRunnerReadinessCheck` (`ci-runners`, which
  replaced `ci-run-workers` in qits-503 and dropped its claim-loop data in qits-506) is **always UP**
  (qits-443). Its data is `connectedRunners` and `totalSlots`, plus a `warning` entry exactly when no
  runner is connected and the process is not stopping — the state in which an accepted run sits
  `QUEUED` until a runner connects. It was DOWN on that state until the deployment of
  `2026.930.103022` was rolled back (2026-09-30): swarm routes only to a healthy task, a runner
  connects through that routing, so a qits-ci that is DOWN until a runner connects never comes up.
  **Readiness must not depend on an inbound connection — do not give this check a DOWN arm again**,
  and do not add a configuration key that restores one. A quarantined runner counts as connected:
  `localhost` registers quarantined and its first health check is a run this process must accept.
  Nothing connected *during* a shutdown is what a shutdown is, so that arm has no warning. **No
  readiness check here reaches qits-cd's `awaitHealthy` any more.** `CiDaemonReadinessCheck` had a
  DOWN arm of its own while the daemon pin ladder could fall all the way through; the version comes
  from the pom now and cannot be absent, so that check is an unconditional readout (renamed
  `ci-daemon-pin` → `ci-daemon-version` to say so) and a bad daemon version is caught by this
  repository's own release request rather than by a deployment.

`CiReservedRunResilienceTest` (formerly `CiRunWorkerResilienceTest`) holds these behaviourally
against a real database on `SuiteRunner`'s one slot, so the sole runner really is the whole of CI.
`CiRunWorkerPoolTest` went with the pool.

### The queue is a surface, and the order explains itself

**The claim order used to be computed and thrown away.** `CiRunOrdering` had exactly one production
caller — the claim above — and `/ci/api/runs/active` answers newest-first, which is a *different
question with a plausible-looking answer*. There was no queue position, no `/queue` route and no ETA
anywhere, so "which build is next" and "when does it get to mine" had no reader at all.

Three classes carry it now and the first two are **pure**: no I/O, no clock, no CDI, no state.

- **`CiRunOrdering.explain(List<CiRun>)`** is the same pass `suggestedOrder` was, answering with its
  reasons attached, and `suggestedOrder` is now that answer mapped. **There is one implementation of
  the ordering** — the alternative, a second pass deriving "why" beside the one that decides, is a
  copy that drifts in the direction nobody notices, because an explanation that disagrees with the
  order is worse than no explanation: it is believed.
- **`CiQueueForecast.forecast(running, queuedInClaimOrder, slots, now)`** is the
  arithmetic. `slots` is `CiQueueForecast.slotCount(runners)` — the connected, non-quarantined
  runners' slots alone; zero forecasts every queued run `NO_BUILD_SLOTS`. `now` is a **parameter** and there is no `Instant.now()` below that line, which is what
  makes the restart doctrine hold here too — the same rows and the same instant produce the same
  forecast in whichever process is asked, so a redeploy mid-queue moves nobody's ETA. It takes
  `OrderedRun`s rather than `CiRun`s deliberately: this arithmetic is only meaningful over the claim
  order, and a `List<CiRun>` parameter would accept a listing's order silently and answer
  confidently about the wrong build.
- **`CiRunService.queueSnapshot()`** is the one impure step: one read of `listActiveNewestFirst`,
  partitioned, then those two functions. **One `Instant` is stamped per response and every row in it
  is relative to that one** — two rows of one body relative to two instants would be two durations
  that cannot be compared. It is stamped *after* the read, so the instant never precedes the rows it
  describes, and the read is *one* transaction so a run cannot appear in both halves or in neither.

**Queue-wait prediction is this service's to compute, and that is the binding rule.** A client
reconstructing the order from a listing would be a second `CiRunOrdering` — four criteria, a
topological pass and a private rank table, over rows that do not carry half of what the decision
reads. So the order rides the rows as `queuePosition` and the reasoning rides beside it as
`ordering`, and `/ci/api/runs/active` **keeps its newest-first contract** rather than being re-sorted:
putting the claim order on the rows is strictly better than changing the meaning of a listing.

**A prediction is an estimate and must never read as a promise.** Two consequences are load-bearing
and both are the kind that get quietly dropped:

- **Every duration on the wire is RELATIVE.** `expectedStartInMillis`/`expectedFinishInMillis` are
  milliseconds from the response's own instant (`generatedAt` on `/queue`), never a predicted clock
  time. A clock time is read in the reader's timezone, compared to a watch, and wrong by however long
  the page has been open. `CiQueueSurfaceTest` asserts the absolute fields *do not exist* on the
  wire, because "we decided not to add one" is not a property a reviewer can check twice.
- **An absence carries its reason all the way to the wire.** `Eta` is a `(Long, Unknown)` record with
  two factories rather than a nullable long, and `predictionUnavailable` is that reason's enum name.
  The three values name **whose** prediction was missing — the run's own, a run ahead of it, or a run
  in flight — because those are three different sentences to the person waiting, one permanent until
  the pipeline has history and two self-healing. A run ahead with no prediction makes every ETA
  behind it unknown too, **and that must be said rather than silently skipped**: a row that
  disappears reads as "finished" and a blank duration reads as "instant". Both forecast lists are
  total for exactly that reason, so their lengths really are the queue's.

**The forecast is attached to every non-terminal row of a response and to no terminal one**,
whichever route produced it — `CiRunController.listing` is the one place that rule lives. A `QUEUED`
or `RUNNING` run therefore carries the same position and ETAs in a repository's own listing as it
does on `/active` or `/queue`, since an ETA that depended on which page asked would be a different
number for the same fact; a finished run carries none anywhere, which is also what keeps it cheap —
a response holding nothing in flight reads no queue at all.

**Every run listing carries step boundaries now, and none carries step output.** All three —
`?repositoryId=`, `/active`, `/finished` — answer `steps` with `output` null and `live` with `output`
null, through `CiRunMapper.toDtoWithoutStepOutput` and `toDtoWithoutOutput`; the single-run read is
unchanged. Their contract was always "without step *output*", and the output was the only reason the
whole object was dropped: it is unbounded, repository-controlled and the only heavy part of a row,
while a step's two instants and its index are a few dozen bytes. Those bytes are what make a
segmented bar *boundary-true*, and carrying them on one listing only would make the bar's
truthfulness depend on which page you are on.

The step DTO is produced output-free by an **explicit mapping** rather than by nulling the field at
the call site: which listings omit the output is a property of the surface and belongs where the
surface's shapes are made, not one refactor away from carrying a repository's logs into a response
with no affordance for them. And the relay-snapshot filter — *a step must never be handed over twice,
once as a row and once as live* — is `CiRunController.liveStep`, one implementation reached by both
reads, because that reasoning is exactly what gets left behind when a second listing starts reading
the relay.

**The one cost worth knowing before widening these rows further**: `?repositoryId=` is unbounded when
`?limit=` is absent, and qits-ci-frontend re-asks without a limit once a limited answer comes back
full. So the step widening is paid per historical run on that route — one indexed read per run with a
`startedAt`, which is why `CiRunService.stepsForAll` skips a run that never started rather than
querying for rows that cannot exist.

**The consumer is qits-ui-components' `QitsStepProgress`**, drawn by both the platform chrome and
qits-ci-frontend. It matches a timing to its predicted step **by `stepIndex`, never by array
position**, because a step is persisted at its end. `stepIndex`/`startedAt`/`finishedAt` on a step
row and `stepIndex`/`startedAt` on `live` are the contract; both objects are optional on that side,
so an older qits-ci draws empty bubbles rather than nothing.

**`CiRunDto.phase` landed with all this and is the smallest part of it.** The column has existed
since `V17__run_phase.sql` and the mapper had simply never copied it, so a client holding a release
request's runs could not tell P1 from P2 except by matching `triggerEventName` against two strings it
had to know. Null means *no phase* — not part of a release — and never *unknown*: the decision is the
trigger event's name and nothing else.

## Addressing

`README.md` has the shape; two things bite when you change a path here.

**`quarkus.rest.path=/ci/api` lives in `service/src/main/resources/application.properties` and the
suite inherits it.** So a resource's `@Path` is relative to `/ci/api` and must never repeat `ci`.
Tests address the absolute path, which is what makes them catch a prefix regression.

**`/ci/api/runs/active`, `/ci/api/runs/finished` and `/ci/api/runs/queue` sit under
`/ci/api/runs/{runId}`, and only JAX-RS' sorting rule keeps them apart.** A literal segment outranks
a template, so the listing wins — but a regression there would show up as the client's rail 404ing
and nothing else, so `CiPipelineBoundaryTest` asserts each route resolves to its own envelope rather
than to a lookup for a run named `active`, `finished` or `queue`. Same for
`/ci/api/repositories/summary` under `/ci/api/repositories`, which is the easier case (that one has
no template to lose to). None of them adds a literal Vert.x route, so
`quarkus.quinoa.ignored-path-prefixes` is unchanged — `/api` already covers them; that was checked
against the key rather than assumed when `/queue` landed.

**The two run listings are complements, and the predicate is written that way on purpose.**
`/active` is `status in (QUEUED, RUNNING)` and `/finished` is `status NOT in (QUEUED, RUNNING)` —
not `in (SUCCESS, FAILED, CANCELLED, CONFIG_ERROR, TIMED_OUT)`, which reads the same today and rots silently: a new
value added to `ck_ci_run_status` would be finished in fact and invisible to both lists, so a run
would leave one and never arrive in the other. Written as a complement they partition the table by
construction. `/finished` carries `?limit=` where `/active` does not, and the asymmetry is the whole
difference between them: what is active is bounded by accepted work and the runners' slots,
what is finished grows with the instance's uptime. Absent means **5**, not unbounded — the opposite
of the repository listing's default, because there is no repository here to make "all of them" a
bounded question — and an ask above **100** is clamped rather than refused, since this is the one
listing that is both unscoped and otherwise unbounded.

**The machine guard is a call in the handler, not a filter over a path.** Both guarded writes sit on
the **machine arm** — `if (MachineIdentity.isMachine(identity))` — because both have two real
callers: a peer with a bearer, and an operator on the edge's forwarded `X-Qits-User`/`X-Qits-Roles`
session, which carries no token at all and is judged by the roles. `CiRunController.cancelReleaseRequestRuns`
asks for `project=*`; `CiEventController`'s trigger asks the claim itself — `MachineAuth.require()`
for presence and audience, then the `project` claim read off the identity — and hands the value to
the engine as a scope, because "granted some project" is not an equality check and the narrowing is
what admits the caller. Demanding a machine token unconditionally
there would 401 the person; skipping the check would unguard the peer. Nothing matches a path, so renaming
a `@Path` moves the guard with the route and can no longer detach it. The fail-open shape that
replaced the old one is narrower and still real: a **new** write method that simply omits the call
ships unguarded, and nothing says so.
`MachineGuardTest` is what stands between that and shipping. It runs with
`qits.auth.machine.required=true` and POSTs each write's absolute address, demanding 401 with no
token, 403 for a token whose `aud` or `project` claim does not cover the target, and **the
endpoint's own answer** for one that does — 503 at the manual trigger, which
evaluates before it answers and has no git host to ask in that profile. Either way the case rules
out 401 and 403, which is the whole of what a guard test can say. **At the trigger the pair to keep
apart is now 503 and 403**: a project-scoped token is admitted (503, the endpoint's own answer with
an empty catalogue) while a token whose project holds none of the repositories this instance does
know is refused (403), which is the cross-project case at the door. Which repositories a scoped call
really evaluates is the `ci` module's suite, where a catalogue with projects in it can be staged; a
forwarded admin session is asserted here too, for the same reason it is on the cancellation.
**The push intake used to be the second guarded write and is not a write at all any more**: a push
arrives as `SCMPublishCommit` off the bus, where a bearer would mean nothing, so the cases that
asked "may this token push to this repository" have no endpoint left.
Add a write endpoint, add its case there, and keep every address in that test absolute: a moved
prefix then shows up as a 404 rather than as a pass.

**The reads are not open, and `@RolesAllowed` shuts before the guard call is reached.** Every
controller carries a class-level role: **the pair** `{qits:admin, qits:system}` on `CiRunController`
and `CiRepositoryController` — `qits:system` is the machine role and `qits:admin` the human one, and
a peer that polls a run it asked for must not be handed a person's role to do it — and `qits:system`
on `CiEventController` and `CiDaemonController`, which is what a machine peer holds
(`CiDaemonSocket` takes `qits:ci-run` alone, the run's token as the edge forwards it) — with the
trigger method naming the pair itself, since an operator invoking it by hand is one of its two real
callers and a method-level list replaces the class's rather than adding to it. **Nothing that mutates was widened with them**: `CiRunController.cancelRun` and
`CiRunController.retryRun` carry their own method-level `qits:admin`, which replaces the class's list
rather than adding to it. `cancelReleaseRequestRuns` keeps the class pair, because a peer service
(qits-projects, withdrawing a release request) and an operator both legitimately call it. So three doors shut in
order, and `MachineGuardTest` pins which: no token is 401, a token granted no roles is 403 at
`@RolesAllowed`, a wrong audience or an uncovered project is `MachineAuth`'s own 403. **A machine
token carries its roles in the `groups` claim** — qits-idp copies them there from
`qits.idp.client.<id>.roles` and quarkus-oidc reads that claim as roles with no configuration at
all — so a fixture that mints a token without `groups` authenticates perfectly and is then refused
403, which is a stale fixture rather than a regression. A method-level role list **replaces** the
class-level one rather than adding to it; a route both a person and a machine read must name both.

**Every read route also takes `qits:agent`, and exactly TWO writes do** — the retry below, and a
runner's health check on demand (`POST /ci/api/runners/{id}/healthcheck`, qits-896), which probes and
changes nothing a person set. Agents keep all read
access and lost write access wholesale (user ruling, 2026-09-12). So `CiRepositoryController` and
`CiDaemonController` name it on the class, and `CiRunController` names it on each of its four
reads: its class list also guards the `cancellations` write. Nothing filters what an agent reads.
The daemon socket is not a read route (step daemons write run records through it) and is unchanged.
`AgentReadAccessTest` holds this. A new read route names `qits:agent` too.

**The exception is `POST /ci/api/runs/{runId}/retry`, and it is scoped rather than blanket** (owner
ruling, 2026-09-19). An agent is the caller standing in front of a release-request gate that went
red for a reason that is the platform's rather than the code's, and it can neither re-ask the gate
nor explain it away: its only other door was an empty commit on a source branch and a second full
gate. So `retryRun` takes `{qits:admin, qits:agent}` and then runs `cancellationScope()` /
`requireRepositoryInProject` against the **run's own** repository, exactly as the cancellation and
the phase rerun do — an agent re-fires in its own project and is 403 in anybody else's, and the
run is read first so a bad run id is still a 404 rather than a 403. It does **not** take
`qits:system`: no peer service presses this button (qits-projects re-asks through `/ci/api/runs/rerun`,
addressed by the triple it actually holds). The claim the check reads is real — qits-idp states
`project` on every commissioned agent credential — and an agent arriving without one holds no
platform-wide role either, so it is refused. `AgentRetryAccessTest` is the case file.

## The Angular client

`service/src/main/webui` is the
[qits-ci-frontend](https://github.com/QuicklyIterateTheSoftware/qits-ci-frontend)
submodule, built and served by Quinoa. The path is Quinoa's default `web-ui-dir`, so it is a
convention rather than a setting, and the four config keys that are settings live in
`application.properties` under "the Angular client" with their reasoning beside them.

**The `/ci` segment is spelled three times and they move together**: `quarkus.quinoa.ui-root-path`
here, `quarkus.rest.path` beside it, and the client's own Angular `baseHref` in its `angular.json`.
The third one is not redundant — the browser resolves the client's asset urls against the document,
so a baseHref that disagrees with where the app is mounted yields a page that loads and then fetches
its own javascript from the wrong place.

**What SPA routing must not swallow is listed here, not derived — and `/ci/daemon` is why.** Quinoa's
fallback is a catch-all under `/ci` and the skip list it *derives* holds exactly two things,
`quarkus.rest.path` and `quarkus.http.non-application-root-path`. The daemon control socket is
outside it: `@WebSocket(path = "/ci/daemon")` is a literal that follows neither key.

This file used to argue that made no difference, because websockets-next registers its route at the
default order while Quinoa's SPA route is near-last, so an upgrade never reaches the SPA handler.
That is true of the **upgrade** and of nothing else. **Measured on the packaged fast-jar before
`quarkus.quinoa.ignored-path-prefixes` was set**, a plain `GET /ci/daemon` — no `Upgrade` header —
and `GET /ci/daemon/nope` each answered **200 `text/html`** with the SPA's `index.html`; the socket
route claims only the handshake and the fallback took the rest. `/ci/daemon` is a cross-repo machine
contract (the path of every step's `$QITS_CI_DAEMON_URL`, dialled verbatim by its daemon), and a
machine client handed a web page parses it as data. The correct answer to a mistyped machine path is
a 404, which is what it is now.

So the key is set: `quarkus.quinoa.ignored-path-prefixes=/api,/q,/daemon`. Setting it **replaces**
the derivation instead of extending it, which is why `/api` and `/q` are spelled out again by hand —
drop either and the API answers mistyped paths with `index.html`. The values are **relative** to
`ui-root-path` (`/api`, never `/ci/api`); an absolute value matches nothing and fails exactly like an
unset key, which is the failure that hides. The list moves when any of its three sources moves —
`quarkus.rest.path`, `quarkus.http.non-application-root-path`, or `CiDaemonSocket`'s `@WebSocket`
literal — so add a literal route and add its prefix in the same commit.

Ignoring a prefix stops the SPA **reroute**; it does not unregister the real route. The upgrade on
`/ci/daemon` still works, and `CiPackagedSurfaceIT` asserts it on the packaged artifact, which is
what keeps that from being a belief. The platform rule this follows in the general case: leave the
key unset when `quarkus.rest.path` and `quarkus.http.non-application-root-path` name the service's
whole machine surface, set it the moment a literal exists outside them.

**quarkus-undertow must never join this module's dependencies.** Its presence breaks Quinoa's
production static serving — the reason qits-artifacts mounts its git host on plain Vert.x routes
rather than as a servlet (that repo's README). Nothing pulls it in today; `./mvnw -pl service -am
dependency:tree -Dincludes=io.quarkus:quarkus-undertow` is empty and quarkus-vertx-http is the only
web stack present. Check that before adding any extension that sounds like a web framework.

**Three places assume the submodule is checked out, and each fails differently:**

- **A build.** An uninitialised gitlink is an *empty directory*, and that is the one case Quinoa
  treats as a misconfiguration rather than as "no client": `./mvnw verify` stops at `No package.json
  found in Web UI directory: 'src/main/webui'`. Loud, and it names the cause — but it is why the
  clone-alone rule at the top of this file now reads "clone **and** `git submodule update --init`".
- **The image build.** `docker/Dockerfile` does `COPY . .`, so the build context carries whatever the
  working tree has. The Mandrel builder stage has no node either, which is why that `mvnw` line
  passes `-Dquarkus.quinoa.package-manager-install=true` and a pinned `node-version` — on the command
  line rather than in `application.properties`, so a developer machine keeps using its own node.
- **This repo's own CI.** The step container's clone is `--depth 50` and does not recurse, so both
  phases of `.config/qits/release.yml` initialise the submodule themselves — the `release-request:`
  slot before the QA image build, the `release:` slot before the publish, each with a
  `-c submodule.qits-ci-frontend.url` override deriving the sibling's address from
  `$QITS_CI_REPOSITORY_URL`. Without those lines the step fails on the empty directory, and the run is
  red for a reason that has nothing to do with the change under test.

Note Quinoa is **disabled by default in test mode**, so no `@QuarkusTest` builds the client and the
suite's runtime is unchanged. What the SPA is actually served as is proven by `package` plus the
packaged artifact, not by surefire — concretely, by `CiPackagedSurfaceIT`'s five probes (the
segment, a deep link, the bare-segment redirect, and the two paths that must answer 404 rather than
the client). Every claim in this section is a measurement that test now repeats.

## The event bus

`eventstream/` is the
[qits-eventstream-javalib](https://github.com/QuicklyIterateTheSoftware/qits-eventstream-javalib)
repository, a **submodule** — the platform's event bus client (`QitsEvent`, `QitsEventBus.publish`,
`QitsEventListener`, `QitsRawEventListener`, `QitsDurableEventListener`, `CausationScope`). It used
to be a directory here, a
library waiting to move out; it has moved, and qits-ci is now an ordinary consumer that happens to
build it in the same reactor. The design is the superproject's `eventsourcing-plan.md`,
`event-causation-plan.md` and `event-delivery-guarantees-plan.md`.

**Its rules are in its own repository and are not restated here.** Read `eventstream/AGENTS.md`
before changing anything about how this service publishes or listens; the seven that bite are the
canonical form as a wire contract, `eventId` fixed at construction, the HTTP/1.1 pin, an outbox that
is empty in a healthy process, causation stamped in the envelope by the bus alone, the three
consuming seams with their subscribe-frame union, and the durable one's exactly-once *effect* with
its "a throw leaves the event owed" failure policy. A second copy of any of that in this file is a
copy that will drift.

**The extraction rule is now a repository boundary rather than a test.** It read "no
`eu.wohlben.qits.ci.*` may be imported in that module"; the module is a different repo with a
different clone-alone gate, so the rule enforces itself and `ExtractionRuleTest` travels with it.
What is left on this side is the arrow: `ci-events/` depends on `eventstream/`, never the reverse,
and it keeps the `ci` namespace precisely because it is qits-ci's vocabulary rather than the
library's.

**Editing the submodule from here is the mistake to avoid**, for the same reason as
`ci-daemon-protocol/` though by a different mechanism: this checkout is a real branch and a commit
made in it is a commit in *that* repository, pushed to *that* remote. Land the change there, push
it, and let the sync move this checkout. A version bump is not part of it — the pom is
`1.0.0-SNAPSHOT` and parentless, and the reactor resolves it as a module in place.

What remains qits-ci's, and is documented below: `service/…/bus/` (both ends of the wiring), the
`RunAnnouncer` seam that keeps `ci/` free of every eventstream type, `EventWireReflection` (the
native-image registration, which lives with the deployable rather than with the library), and the
`%dev`/`%test` darkness. The trigger engine's half is under "The trigger engine".

### How the deployable uses it

`service/…/bus/` is the whole of qits-ci's wiring: two announcers publishing, and five listeners
consuming. The subscriber dials itself on `StartupEvent` because listener beans exist. Registering a
listener really is "add a bean" — no channel name, no annotation — and no `@Unremovable` is needed,
because `EventDispatcher`'s `Instance<QitsDurableEventListener>` is what ArC counts as a use.
`EventstreamDarknessTest` asserts that rather than trusting it, since a removed listener subscribes
to nothing, is swept for nothing, and says nothing about it.

**All five consume DURABLY, and none of them is a typed or raw listener any more.** The library's
other two seams are live-only and at-most-once — a frame broadcast while this process is
disconnected, restarting or mid-cutover is gone — and the 2026-08-10 rebootstrap campaign measured
what that costs a platform whose release train rides the bus. `QitsDurableEventListener` is the
answer: one funnel claims the event in `consumed_event` and calls the handler in one transaction, and
a watermark is paged forward from qits-events' log at startup and on a schedule, so a disconnect is a
delay rather than a hole. `event-delivery-guarantees-plan.md` in the superproject is the design and
`eventstream/AGENTS.md` is the contract; what follows is only what is qits-ci's to get right.

**Each listener's `consumerId()` is STORAGE, not a label**, and the four shipped are literals in
`EventstreamDarknessTest` for that reason:

| bean | `consumerId()` | `signatures()` | `selects` |
|---|---|---|---|
| `CiEventTriggerListener` | `ci-event-triggers` | `["*"]` | default (see below) |
| `BuildSuccessfulListener` | `ci-release-train` | `BuildSuccessful` | default |
| `ScmReleaseListener` | `ci-release-facts` | `SCMRelease` | default |
| `RepositoryRenamedListener` | `ci-repository-rename` | `RepositoryRenamed` | default |

There were five. `DaemonReleaseListener` (`ci-daemon-adopt`, `SoftwareRelease`, selecting the
daemon's own releases) adopted the version named on a daemon release and had it probed in a
throwaway container — so a daemon reached every step container without this repository's tests ever
running against it. It is deleted; see "The ci-daemon control plane". Its id is abandoned, not
migrated away, exactly as `ci-push-runs` is.

**The fourth is the only one that REPLAYS, and it is the only one that may.**
`RepositoryRenamedListener.replayFromEpoch()` returns true, which is the library's reserved case —
"a projection being built" — being taken up for the first time here. The rows it repairs
(`ci_run.project_id`/`repo_name` V5, `ci_release_announcement.project_id`/`repo_name` V10/V11) are
written once at accept time and re-derived by nothing, so the renames that made them stale are
*already on the log*; a consumer initialized at the head would subscribe, claim and settle perfectly
and repair nothing at all, which is the failure with no symptom. It is bounded by the signature —
catch-up queries the log with that one name filter, and the whole history of it was two frames on
2026-09-07 — and it is consulted once, at initialization, so a later re-repair is
`CatchupSweeper.rebuildFromEpoch("ci-repository-rename")` rather than a flag. The other three act on
arrivals and must never carry it: one of them replaying would re-evaluate every event any repository
ever declared an interest in, or re-announce every release the platform ever made.
`EventstreamDarknessTest` asserts the partition rather than the flag, so a fifth listener copying
this one's shape without its reason is a red build.

**And it is the only one that BINDS a foreign payload.** `service/…/bus/RepositoryRenamed` is a local
transcription of qits-projects' record — that service publishes no vocabulary jar, the same
measurement `ScmReleaseContractTest` records — and the listener decodes it with
`CanonicalJson.payloadTo`, which is a `@RegisterForReflection` target it owes and `EventWireReflection`
carries. `ScmReleaseListener` walks its payload with `readTree` and owes none. Which of the two shapes
a jarless event gets is a choice per event, not a rule about jars; the guard for this one is
`RepositoryRenamedContractTest`, which names the publisher's source file and pins the payload's key
set — exactly these five, no more and no fewer — through the real `CanonicalJson`.

**What the repair is FOR is the announcement, not the display.** The cheap half is
`GET /ci/api/repositories/summary` showing a renamed repository's old name until it next builds. The
expensive half is an announcement left OWED across a rename: it publishes `SoftwareRelease` naming the
old repository, qits-deployments reads that released repository's spec name-addressed at
`/git/<projectId>/<repoName>`, and the read 404s — the id-addressed fallback being refused by
qits-githost's storage-client guard for everyone but qits-projects. The release publishes, the deploy
never happens, and neither service names the rename as the cause. **The candidate catalogue is not
part of this**: it is a live listing behind a five-second cache and was never stale.

The repair writes on ci's datasource in **its own transaction**, `ScmReleaseListener`'s exact
arrangement and for the identical measured reason (`Enlisted connection used without active
transaction`), and it is idempotent by construction — the same two values, keyed by the storage id the
rename did not move — so it needs no tip check of the kind the library's ordering section demands of a
last-writer-wins handler. Two renames of one repository inside one catch-up sweep could still apply
reversed; that is accepted and stated in the class javadoc, because the next event or the next run of
that repository heals it and the alternative is a call to qits-projects on the dispatch thread.

**There were five before this one too, and `ci-push-runs` retired on 2026-09-05.**
`ScmPublishCommitListener` was the
push intake — it had itself replaced `POST /ci/api/events/post-receive` — and it called
`CiRunService.onPostReceive` for every `SCMPublishCommit`, accepting one `QUEUED` run per pushed
branch ref against `.config/qits/ci-post-receive.yml`. The platform runs no CI outside release
requests and every repository's file was gone; the listener was not, so every push still cost a
runner slot to discover that nothing was declared. It is deleted, along with the intake and the
parser. Three things about the retirement are worth keeping in front of you:

- **`SCMPublishCommit` still arrives, and that is deliberate.** `CiEventTriggerListener` subscribes
  to `"*"`, so a repository declaring `event: SCMPublishCommit` in a `ci-event-*.yml` is served by
  the ordinary grammar — matching, `when:` and `checkout:` — with no code special to pushes anywhere.
  None does today. That capability is the reason the *engine* arm stays while the hard-coded one goes.
- **`ci-push-runs` is an ABANDONED consumer id.** Its `consumed_event` rows and its
  `consumer_watermark` are left where they are — no migration, no deletion — which is what
  qits-platform-deployments did with `pd-build-succeeded`. The rows are pruned by the sweeper's own
  horizon and the watermark is simply never read again. **The id must never be handed to a new
  listener**: one inheriting it would inherit a watermark saying every push ever announced had
  already been handled, and would silently skip everything up to the head of the log.
  `EventstreamDarknessTest` asserts no live listener claims it.
- **The causation chain lost a hop and did not break.** A push run carried the frame's id in
  `trigger_event_id`, so release → push → commit event → CI run → deploy was one chain. There is no
  push run to be a hop; a release request's `ReleaseRequestChanged` and a release's `SCMRelease` are
  what cause runs now, and both are stamped exactly the same way. A null `trigger_event_id` — a run
  nothing announced, publishing a root — is left only on historical push rows.

The dependency this adds is `eu.wohlben.qits:qits-githost-events`, the vocabulary jar — four records
and the bus, no client and no address. It is the only compile-time dependency this repo has on
another context, and "Adding a dependency on another context" below is about *clients*, not about a
published event's shape.

Change one and you mint a brand-new consumer: the old claims are orphaned and the new id initializes
at the head of the log, silently skipping everything in between. Reuse one and a listener inherits
another's watermark, believing it has handled events it has never been offered. `ci-release-train` in
particular says what the *consumption* is, not what the class is called — and note it is not where
release-train membership is decided. That is the trigger engine's, below.

**Every handler failure is a decision between two, and it has to be made deliberately.** A throw
rolls the claim back and the event stays owed — offered again forever, with this listener's watermark
stuck behind it, because the seam has no dead letter and says so. So: **retryable** (the store is
down, the queue is full — a later attempt could succeed) is left to throw; **poisonous** (a payload
that will not parse, an event with no name, a release with no `occurredAt` — the same bytes will fail
identically every time) is a WARN and a return. Each of the three javadocs names its own cases;
`DurableBusConsumptionTest` asserts them, `HANDLED` versus `FAILED`, through the funnel itself.

**The suite must not be swept behind.** `service/src/test/resources/application.properties` sets
`qits.eventstream.catchup-at-startup=false` and stretches `qits.eventstream.catchup-interval`,
because a tick landing mid-test would enqueue a trigger evaluation nothing asked for — and `StubEventsServer` scripts a canned list and ignores the cursor, so it would do it
repeatedly. The scheduler stays *on*: the outbox sweeper is scheduled too and has no business being
disabled. A test that wants a sweep calls `CatchupSweeper.catchUp()` itself, which is what the
eventstream suite does.

**The publish hook hangs off a seam, and it is now the ONLY thing a green run announces.**
`RunAnnouncer` (in `ci/control`, implemented in `service/`) is what keeps the `ci` module free of the
bus. It used to be the second of two — `PdNotifier` was beside it, a direct POST asking
qits-platform-deployments to deploy, and the two were separate ports because a request to one named
service and a statement to the platform are different things. The deployer consumes off the bus
durably now — `SoftwareRelease`, since a green build stopped being a reason to deploy anything — so
the request became a consumption and the port retired; the statement is what is left, and the shape
of it did not change. What hangs off `BuildSuccessful` is qits-projects' release-request gate. `finishedAt` is on the signature because an event carries when it
happened,
and it comes back out of `finishRun` rather than off the `CiRun` instance: that method mutates a
freshly loaded entity in its own transaction, so the caller's copy never sees the value. **A null
`occurredAt` is a 400 from qits-events on every green build**, which is why the seam test asserts
the timestamp rather than only the coordinates.

**That heading is now false in its second half, and the correction is the whole of the run-lifecycle
feature.** `RunAnnouncer` grew a third method, `onRunStatusChanged`, and a green run publishes four
events rather than one: `BuildStatusChanged` at `QUEUED`, at `RUNNING` and at the terminal
transition, then its `BuildSuccessful`. **Same seam, one method, every transition** — not one event
type per state, because the thing worth announcing is that `ci_run.status` moved and what it moved
between, and a per-state vocabulary would make a mirror subscribe to five names and grow a sixth the
day the enum does.

The two are statements about **different subjects** and that is why neither could be widened into the
other. `BuildSuccessful`/`BuildFailed` are statements about the *commit*, so they are selective on
purpose — a cancelled run and a superseded one announce nothing, and qits-projects' gate depends on
that. `BuildStatusChanged` is a statement about the *row*, for a subscriber mirroring
`GET /ci/api/runs/active`, and it is exhaustive for the mirror-image reason: **a listing has two
edges and both are owed.** A run that leaves by being cancelled leaves as completely as one that
leaves green, so announcing only the flattering half would strand a mirror holding that run forever.

Every writer of `ci_run.status` in `CiRunService` therefore calls `announceStatus`, and a new one
that does not is a bug in the new writer. Four of those call sites are worth knowing:

- **The reservation announces its own `RUNNING`**, and `settleQueued` its own `CANCELLED`, rather than
  any caller doing it. Each is the single writer of its transition, so it is the one place a
  second caller cannot forget — the same argument that put `finishRun` where it is.
- **The accept path announces whatever status the row REALLY holds**, never `QUEUED` on the strength
  of having just accepted. `supersedeByVersion` can settle the run being accepted inside the very
  transaction that inserted it (an out-of-order tag burst), so such a row commits `CANCELLED` having
  been `QUEUED` in no state any reader could observe. Announcing it queued would put a run into a
  mirror's listing that nothing afterwards could ever take out: no runner claims it, no terminal
  write happens, the row is already final. The status travels out of the transaction on the entity,
  and the **losers** that accept superseded travel with it (`CiRunService.Accepted`), because the
  announcement has to happen after the commit and a second query for rows the transaction already
  held would be paying twice for one answer.
- **The boot sweep announces the backwards one.** A `RUNNING` row handed back to `QUEUED` is a
  transition nothing else would ever report — the run's next announcement is a runner's reservation,
  minutes later, from a state the mirror was never told about.
- **`cancel` announces from the two arms that WRITE a terminal row** and not from the third, which
  only records a reason on a run that is still going; that one's terminal announcement is made by the
  runner driver that owns it. So a run announces exactly once for the write that finished it, and never for
  the request that asked.

`BuildStatusChanged` is on `EventWireReflection`'s list like every other type that crosses the wire,
and it is the publish-only case at its most expensive: an unregistered record would throw or mangle
its payload several times per run rather than once. One shape of it differs from `BuildSuccessful`
and is deliberate — the record's timestamp component **is** `occurredAt`, so `CanonicalJson`'s mix-in
excludes it from the payload and it rides the envelope alone, where an event's time has always been.

**There are two publishing seams and they are separate because they say different things.**
`ReleaseAnnouncer` (`ci/control`, implemented by
`service/…/bus/SoftwareReleaseAnnouncer`) announces one published *artifact*; `RunAnnouncer`
announces a run that passed. One green run can go down both, and a release pipeline's does — first
`BuildSuccessful`, then one `SoftwareRelease` per declared artifact. Folding them would put "the
build passed" and "the registry has it" behind one name, which is precisely the conflation the
split-release redesign exists to undo: the old single event fired at release-*push* time and every
consumer read it as "the package exists", with an entire upstream build in the gap.

Four things about that second seam are worth having in front of you:

- **`SoftwareRelease` names the repository four times and that is the additive shape, not a
  duplication to tidy up.** `repository` is what it always was — the run's `repo_id` — and every
  committed selection and every existing consumer reads it, so it is never repointed. `repoId` is the
  same string under the name the platform addresses a repository by, and `projectId`/`repoName` are
  the facts nothing on this event could previously supply. They exist for one reader: a consumer that
  has to *deploy* what was published needs an address for the repository at all, and making it look
  one up — against qits-projects, on its own dispatch thread, for something the publisher already
  held — adds a hop that can fail to a release that cannot.
  <br>**`repoName` is the half that decides whether the read is allowed at all**, and it was added
  after a live incident: qits-deployments reads a released repository's `deployments.yml`
  name-addressed (`/git/<projectId>/<repoName>/blob/…`) when the event carries the name pair, and
  falls back to the id-addressed scheme when it does not — which qits-githost's storage-client guard
  403s for everyone but qits-projects. An event carrying `projectId` and no name therefore reached
  the deployer with an address it is refused on, and every bus-driven deploy failed its spec read.
  <br>**Both are carried on the OWED ROW** (`ci_release_announcement.project_id` V10, `repo_name`
  V11), beside `finished_at` and `trigger_event_id` and for the identical reason: the announcement is
  often made by whoever closes the join later — a tag-triggered run waits for the `SCMRelease`, and
  the boot sweep announces in a later process entirely — and neither can read the run row back.
  `ReleaseJoinTest.theOwedAnnouncementCarriesTheProjectItWasOwedFor` is the case that fails if either
  is ever read at announce time instead.
  <br>**Null is a supported value and reaches the wire as an ABSENT KEY**, because `CanonicalJson`
  includes `NON_NULL`. An id-addressed candidate has neither project nor name, so "qits-ci does not
  know" is spelled by the keys not being there; writing a null would have made absence a value.
- **`priority` is carried verbatim, and what is transcribed onto this event is still judged
  nowhere.** It is the release request's effective priority, declared in qits-projects on its
  participating branches; `SCMRelease` carries it, the release join records it on `ci_scm_release`
  and resolves it back at announce time, and it leaves on this event — **byte for byte, with no
  local enum and no comparison anywhere on the announce path**, so an unknown word rides through
  rather than costing a release its announcement. Null where no release fact stands behind the
  announcement (a hand-supplied event through the manual door leaves no row) and where the release
  stated none, and null is an ABSENT KEY for the same NON_NULL reason `projectId` and `repoName` are.
  The hand-kept half is `ScmReleaseContractTest`'s transcription, as ever.
  <br>**This bullet used to end "the run queue is still FIFO" and that half is now false**, so read
  the two paths apart. The announce path is unchanged and is the sentence above. The *accept* path
  grew a second, independent reader on 2026-09-07: `CiRunService.priorityOf` reads the same field off
  the same event onto `ci_run.priority`, and `CiRunOrdering` ranks the queue by it (see "The queue is
  the table"). The two copies are deliberately different facts — `ci_scm_release.priority` is what
  the RELEASE said, resolved at announce time; `ci_run.priority` is what THIS RUN was accepted
  knowing, which is the only one a release request's QA run could have at all.
- **The fan-out is `CiRunService`'s and the port takes one artifact.** N declarations are N calls, so
  a failure costs one announcement rather than the rest. The bus already supports siblings —
  the outbox enqueues one row per event in its own transaction and `CausationScope.current()` is a
  non-consuming read — and `CiEventTriggerCausationTest` asserts the parent lands on *both* of two
  siblings rather than trusting that.
- **`CausingEvent.parentOf` is one implementation, deliberately.** Both announcers turn the run row's
  `triggerEventId` into the published event's parent, and it is defensive: an id that will not parse
  costs the run its causation edge and nothing else, because throwing there would lose the
  announcement for the sake of the edge.
- **The version is read at completion, not at accept.** It comes out of the triggering event's
  payload, which is on no column and exists only in the reserved run's request — so it travels down into
  `runSteps` with the declaration. Reading it later is what lets a red run announce nothing *and*
  warn about nothing; a declaration whose trigger carries no version is a WARN and no event, since
  a blank version would publish a package reference nothing can resolve. **Two events feed it and
  they spell it differently**: `version` on an `SCMRelease`, `tagName` on an `SCMPublishTag`, whose
  value IS the version string because a release stamp is the name of the tag the release push
  created. `CiRunService.releaseVersionOf` is the one place that choice is made, and it had to exist
  before the join below could have anything to join on.

**A third seam, `RunnerAnnouncer`, carries the runner lifecycle** (qits-465): eight events,
`RunnerCreated` … `RunnerDeleted`, one per fact, implemented by `service/…/bus/RunnerLifecycleAnnouncer` —
eleven since the quarantine (qits-466) added `RunnerQuarantined`, `RunnerReinstated` and
`RunnerHealthChecked`, announced by `CiRunners` after their writes commit.
Three things differ from the two above and are deliberate:

- **One event per fact, not one `RunnerStatusChanged`.** A run has one status column, so one event
  says it moved; a runner's existence, registration, connections, update and settings are five facts
  in two places (the row, and `CiRunnerRegistry`'s memory). `RunnerCreated`'s javadoc argues it, and
  says why the names are `Runner*` rather than `CiRunner*` (estate convention, and `CiRunnerCreated`
  is already the create door's response schema).
- **Two callers.** `CiRunners` announces the four row events after each `requiringNew` commits, so a
  refused write throws before it announces; `CiRunnerRegistry` announces the four connection events
  when its own state changes, and is the only place that knows *why* a connection ended (`RETIRED`,
  `REPLACED`, `LOST`, `REFUSED`, `SHUTDOWN`). Both go through `ci/control/RunnerAnnouncements`, which
  catches whatever an announcer throws.
- **The bus announcer does not publish on the caller's thread.** `BuildAnnouncer` accepts
  `publish()`'s bounded wait because its caller is a run's driver thread; half of this port's callers are socket
  frame handlers, where that wait is a `Hello` left unanswered. So events go to ONE publishing thread
  (one, because a self-update's order is part of what is said), and the causation parent is read on
  the caller's thread and passed explicitly — a thread-local does not follow the work.
  `RunnerLifecyclePublishTest` pins both on the wire.

### The release join, and why the port has a gatekeeper now

**Two words carry across this section and the release-slots one below, and they mean exactly one
thing in all three services.** A **phase** is a unit of work with a state and a rerun — QA, publish,
deploy — so a step is not a phase. A **gate**
is the condition between two phases, and there are four (CI, approval, publish, deployment): **a gate
delays, it does not fail.** The pipeline those phases belong to is the release request in
qits-projects, not a table here; what qits-ci learns is which phase a run is, from the triggering
event. Read the join in those terms: a publish-phase run that goes green while no `SCMRelease` has
arrived leaves its announcement **owed**, which is the publish gate still waiting on a fact — not a
release refused, and not a verdict about the run.

**A green release pipeline no longer announces on its own.** `CiRunService.announceRelease` hands
what the run published to `ReleaseJoin` (`ci/control`), and that is what calls `ReleaseAnnouncer` —
only once an `SCMRelease` for the same `(repository, version)` has been seen. bootstrap-replay-plan.md's
WP2; the class javadoc carries the argument in full and the short form is:

- A **restore** re-establishes SCM state and produces `SCMPublishTag` alone. A **release** also
  announces novelty, and `SCMRelease` is that announcement. Announcing off the tag made
  every rebootstrap impersonate a release — the train woke against a platform the boot had not
  finished deploying, and the same eight repositories went red every time.
- **The publisher moved from qits-workspaces to qits-projects and this repository changed nothing**,
  which is a property of how the consumer is written rather than luck: the durable seam subscribes by
  **signature**, an `EventFrame` carries no producer and no source service, the join key is
  `(repository, version)`, and the causation edge is the frame's own id rather than an expectation
  about who minted it. Anything that had recorded "qits-workspaces said so" — a producer filter, a
  source check, an expected parent — would have gone silently dead at the cutover, in the direction
  that publishes without announcing. The event's shape did not move either at the cutover: same
  signature, same seven components, so `ScmReleaseContractTest`'s transcription was unchanged and
  only the file it names moved (`qits-projects-service/service/…/bus/SCMRelease.java`).
- **What DID change is `branch`, and nothing here reads it.** It is the release request's backing
  branch (`release/<id>`) now, and that branch is **deleted in the same operation that creates the
  tag** — so it does not exist by the time the event is evaluated. The version drives every
  coordinate. `ReleaseAnnounceSeamTest` and `ScmReleaseContractTest` pin both halves, and
  `CiEventTriggerCausationTest`'s frame carries a `release/<uuid>` branch for the same reason.
- **`commitSha` is the eighth component, and it is what a release run is now anchored at.** For as
  long as the event named a deleted branch and a tag but no commit, a release pipeline could declare
  no `checkout:` at all: its run was recorded at `main`, the daemon cloned `main`, and the step
  script fetched `refs/tags/$version` and checked it out detached — so every release run on the
  platform displayed as `main@<head>`, a run recorded against a commit it did not build. qits-projects
  publishes the tag's commit now, and the composed release phase spends it on
  `checkout: { branch: version, sha: commitSha }`. **A tag is a ref and that is the
  whole mechanism** — `checkout.branch` resolves to a *ref name*, `git clone --branch` takes a tag,
  and neither the engine nor the daemon learned anything about tags.
  <br>**`optional: true` was on that composed pair and is gone (2026-09-22).** It was the
  transition: `commitSha` is additive, so a replay or an older publisher carries none, and such a
  run fell back to `main`'s head with its checkout **stripped**. Both halves of that ended. The
  compatibility is unreachable for a composed document — the slot file is read AT the event's sha,
  so an event with none composes nothing to soften — and what the arm really did was dispatch a
  RELEASE run at a revision the event is not about, gating a commit nobody released. The engine now
  **refuses the flag outright for the two release events** (one ERROR, no run, the event settled
  like `NO_REVISION`), and the composer emits it nowhere. What survives is the genuinely different
  case: a hand-written file reacting to ANOTHER repository's `SoftwareRelease`, where `main`'s head
  is not a fallback but the only revision the event names for this repository — that run is still
  accepted with its checkout stripped (`withoutCheckout`), which is what keeps the per-ref burst
  collapse from deduping two distinct upstream releases. `CiEventCheckoutTest` pins both sides.
  The step's tag fetch stays as the anchored path's no-op; it is no longer any fallback's mechanism.
- **The two facts race on a real release, so both halves are rows.** `ci_release_announcement` holds
  what a green run owes (`announced_at` null is "still owed"), `ci_scm_release` holds what was really
  released. Either arrival order works and a restart between them costs nothing.
- **An `SCMRelease`-triggered run takes no lookup at all.** The event that caused it IS the release
  announcement, so the join closes by construction — which is what keeps every recipe that still
  declares `event: SCMRelease` behaving exactly as before, and what keeps the manual trigger door
  working (a hand-supplied event rides no bus and leaves no fact row).
- **A tag-triggered run with no release behind it never announces. No timeout, no fallback.** The
  owed rows stay as the readable account of what published without being released.
- **Published first, marked after**, inside one transaction holding the owed rows locked
  (`lockOwed`). So two drivers of one key cannot both announce, and a crash between the publish and
  the commit leaves the row owed for the boot sweep. At-least-once by choice: losing an announcement
  is the failure the platform forbids, making one twice is the nuisance the other way round.
- **The boot sweep runs on its own thread** (`ci-release-join-sweep`), and that is the
  daemon pin ladder's own startup-discovery lesson applied: a startup observer that blocks on the
  network loses the healthcheck race and cd kills the deployment.
- **`ScmReleaseListener` writes in its own transaction, not the claim's.** The claim lives on the
  eventstream datasource and the fact row on ci's, and one JTA transaction does not take both —
  measured, as `Enlisted connection used without active transaction`. `CiEventTriggerService`'s owed
  ledger runs the same arrangement for the same reason. The fact is written before anything is announced, so the
  direction that can go wrong is a re-offered event finding the row already there, which is a no-op.
- **`SCMRelease`'s name and its three payload fields are strings here**, like the tag event's, and
  their guard is `bus/ScmReleaseContractTest` — the same shape as the tag event's with one
  indirection, because **qits-workspaces publishes no vocabulary jar**. Measured 2026-08-12: the
  platform Maven registry serves `qits-githost-events` and `qits-eventstream` and answers `nothing
  is deployed` for `qits-workspaces-events`, so depending on it would compile from a developer's
  `~/.m2` and fail to resolve in the release pipeline's own step container. What that test holds
  instead is a **transcription** of the record's component list, named against its source file, run
  through the real `CanonicalJson` — so the wire rules stay the library's and only the component
  list is hand-kept. `DurableBusConsumptionTest` drives those same canonical bytes through the real
  listener, so the strings are proved to work rather than merely to be present.
  `ReleaseJoin.RELEASE_EVENT_NAME` is the one place the name is spelled. **A rename in
  qits-workspaces is a change to that transcription in the same campaign**; landing it there and not
  here leaves this suite green and the join dead, which is the one failure the test cannot prevent
  and says so out loud.

The call sits on a run's driver thread and it blocks. That was the trade, and it is bounded
rather than free: `publish()` never throws, attempts the PUT inline, and gives up after
`qits.eventstream.publish-timeout` (~5s), after which the outbox owns delivery. So an unreachable
qits-events costs each green build a few seconds and nothing else. Anything slower than that does
not belong behind that port.

Two configuration facts about the bus that are easy to get backwards, and both are this repo's
to get right rather than the library's:

- **The darkness belongs to `service/`, not to the library.** The jar ships
  `qits.eventstream.enabled=true` — a library that shipped dark is one whose first deployment
  discovers it was never wired up — and `service/src/main/resources/application.properties` carries
  the `%dev`/`%test` `false`, exactly as it does for the OTel keys. Nothing else about the bus is
  restated there: `qits.events.url`, the outbox datasource, the timeouts and the retry budget are
  ordinal-100 defaults in the jar, and a copy in the app's file would be a second place to change.
- **Dark does not mean absent.** `enabled=false` stops publishing, sweeping and dialling; it does
  not stop the datasource. Quarkus opens the connection and runs Flyway at boot regardless, which is
  why `service`'s `EmbeddedPgConfigSource` hands out SIX values rather than three: the outbox gets a
  database of its own on the same embedded postgres or the suite does not start.

  **The deployment side of that same sentence cost a rollout, so it is worth stating plainly: adding
  this module to the deployable adds a MANDATORY deployment resource.** It used to be a mandatory
  VARIABLE — `QUARKUS_DATASOURCE_EVENTSTREAM_JDBC_URL`, which had to point at the data volume — and
  the rollout it cost was the shipped `${user.home}` default behind it: right for a host-run process,
  and in a container with no `HOME` the native binary resolved it to `?`, which H2 rejected outright.
  The process died at Flyway before serving anything. That was **a config default no JVM test
  exercises, failing only in the packaged artifact in its real environment**, alongside the
  `AUTO_SERVER=TRUE` that killed the binary and the IPv4 bind.

  What replaced it cannot fail that way, because there is no default left to be wrong:
  `.config/qits/deployments.yml` declares `resources: postgresql:db,
  postgresql:eventstream:qits_ci_eventstream`, qits-platform-deployments creates both roles and
  databases before the container starts and injects `QITS_RESOURCE_DB_*` and
  `QITS_RESOURCE_EVENTSTREAM_*`, and the two jars read those variables in their own shipped
  defaults. **The resource NAMES are load-bearing** — the variable names follow them — so renaming
  either in that file silently stops matching the jar that reads it. An unset variable leaves the
  expression unresolvable and the process refuses to boot, loudly and safely, since the health gate
  keeps the previous container.

  **The fourth member is not a config default at all, and it is the one that failed quietly.**
  `service/…/bus/EventWireReflection` is a class with no code: a `@RegisterForReflection` naming
  `BuildSuccessful`, `SoftwareRelease`, `EventEnvelope`, `EventFrame` and the
  `CanonicalJson$QitsEventMixin`, plus a
  private constructor. Without it the deployed binary threw Jackson's `No serializer found for class
  … BuildSuccessful … you may need to configure reflection` on **every** green build — inside
  `CanonicalJson` and therefore *before* an envelope existed, so the event never reached the outbox
  either. Not a delayed delivery, a lost one, with a single WARN per run to say so.

  Nothing registered them because **`CanonicalJson` builds its own `ObjectMapper` on purpose**
  (above, and not negotiable): the graph that mapper serializes is invisible to the build step that
  scans for what needs reflecting on. **The mix-in is in the list because two binaries were built to
  find out, and it is the worse of the two failures.** Jackson reads its `@JsonIgnore`s with
  `getDeclaredMethods()`; with the three record types registered and the mix-in left out, a green
  build published `{"branch":…,"commitSha":…,"eventId":"00a32ad6-…","finishedAt":…,"repoId":…,
  "runId":…}` — no crash, no log, `eventId` simply present in a payload that is supposed to carry no
  identity at all. A wire contract violation that breaks nothing visible is not a lesser bug than one
  that throws. Register; do not "fix" a recurrence by injecting the CDI mapper.

  It lives in `service/` rather than beside the code it describes for the reason everything native
  does: the deployable is what gets built into an image, and `eventstream/` is another repository
  entirely.

  **The mix-in is named as a STRING, and that is the one line in this repo a rename of the library
  cannot break loudly.** `classNames = "eu.wohlben.qits.eventstream.control.CanonicalJson$QitsEventMixin"`
  compiles whatever it says; a stale package there costs the payload its `@JsonIgnore`s in the
  binary and nothing else. `EventWireReflectionTest`'s `MIXIN` constant is the guard — it resolves
  the same string with `Class.forName` — so the two move together or the suite goes red. `EventWireReflectionTest` guards the list's **completeness** — every
  listener bean's event type is in it, the mix-in's name still resolves — and says in its own javadoc
  that completeness is all a JVM test can guard, because on a JVM these classes reflect whether
  anyone registered them or not. The correctness proof is the binary, running: the round trip through
  a real qits-events.

  **That proof is cheap enough to repeat before a rollout, and it is how both facts above were
  established.** `sdk env` then `./mvnw package -Dnative -DskipTests`, run `service/target/qits-ci`
  with `QITS_EVENTSTREAM_ENABLED=true`, `QITS_EVENTS_URL` pointed at any process that answers a PUT
  with a 201, and trigger a `steps: []` pipeline through `POST /ci/api/events/trigger` — a zero-step
  pipeline reaches SUCCESS with no docker and no daemon, which is the shortest path there is from a
  fresh binary to a published event. (It used to be a POST to the push intake, which no longer
  exists; a push now needs a real `SCMPublishCommit` on the bus.)
  Read the PUT body. Anything about this module that only the binary can be wrong about is one minute
  of native-image away from being known rather than believed.

  **The far end of that failure was mute, and that is fixed too.** `EventDispatcher` logged a frame
  it could not read at DEBUG, so a binary that could not deserialize `EventFrame` would have consumed
  the entire stream in silence for as long as it ran. It is a WARN now, naming the frame's `name` and
  `id` when the text is JSON at all (a second, untyped read — `readTree` needs no reflection, which
  is precisely why it still works when binding does not). An unknown *signature* stays DEBUG: that
  one is ordinary traffic, since a subscription set is a filter rather than a promise.

`quarkus-scheduler` (the outbox sweeper's, and now the catch-up sweeper's) arrives transitively with
the jar and is new to this
deployable; `quarkus-websockets-next` was already here for the ci-daemon control plane, so the
client half costs the image nothing. `quarkus-undertow` stays absent — check it with the
`dependency:tree` line under "The Angular client" after touching this pom.

## The trigger engine

There are **two** trigger types now, and the second one is the reason a frame-shaped consuming seam
is needed at all.
A repository commits `.config/qits/ci-event-<anything>.yml` naming a domain event and a selection
over its payload; a matching event on the bus runs that file's pipeline — against the head of
`main`, or, when the file declares `checkout: { branch: <path>, sha: <path> }`, against the commit
the event's payload names ("decide at main, build at the event's commit" — discovery, parsing and
`when:` still read `main`'s head, so a pushed branch cannot alter the CI that gates it; only the
recorded run's branch/sha come from the payload, validated through `CiIdentifiers` because a
payload is attacker-shaped). Recording the payload pair on the row is the whole of the mechanism:
the clone env, the restart snapshot and `announceRun`'s `BuildSuccessful` all read those two
columns. The `branch` path resolves to a **ref name** and a tag is one, which is how a release
pipeline anchors at `{ branch: version, sha: commitSha }` without the engine or the daemon holding
any concept of a tag. `optional: true` makes an unresolvable checkout a fallback to `main`'s head
instead of a refusal — **for every event but the two release ones, where it is refused outright**,
because a release event names the revision it is about and a run of it at `main` gates a commit
nobody released. What is left is a file about another repository's release, and such a run is
accepted with its checkout stripped
(`CiEventTrigger.withoutCheckout`) so every reader keyed on `checkout` sees the run it really is. A
checkout run's burst collapses per ref (`supersedeByCheckoutBranch`, the run queue's
third supersede — gated on the trigger declaring checkout, because non-checkout event runs share
"main" as a convention and must never be ref-collapsed across distinct events; the stripping above is
what keeps fallback runs on the right side of that gate). Platform triggers refuse the key (WARN + no
run). The design is the superproject's
`ci-event-triggers-plan.md`, the format is `README.md` (see "Building the commit the event
names"), and what follows is what biting it feels like.

- **`ci/` stays free of the bus's SEAMS, in both directions.** `service/…/bus/CiEventTriggerListener`
  is the `QitsDurableEventListener` bean; it turns an `EventFrame` into
  `CiEventTriggerService.Arrival`, four plain strings, and hands it over. That is the same seam shape
  `RunAnnouncer` is on the publishing side, pointed the other way, and it is why
  `CiEventTriggerService` — which does the real work — imports no publish/subscribe type. Keep it
  that way; the extraction rule protects the library, and this one protects the domain.

  **The word is SEAMS now, not "the bus", and the narrowing was deliberate (2026-08-10).** The
  eventstream jar also carries the platform's causation *persistence vocabulary* — `CausedRow`,
  `CausationStamp`, `@Uncaused`, three jakarta-persistence-shaped types with no publish, no
  subscribe and no wire in them — and `CiRun` implements it, so the jar sits in `ci/`'s pom now.
  What the rule still forbids is control flow: no listener, no publisher, no `EventFrame`, no
  `QitsEventBus` anywhere in `ci/`. What the dependency costs is honest and paid in the suite: the
  jar's persistence unit boots in this module's tests too, so `testdb/EmbeddedPgConfigSource` feeds
  it `eventstream_ci_domain` and the test properties keep the bus dark — the same consumer contract
  the service module has always honoured.
- **The manual trigger is a second inbound adapter of the same evaluation, on a different thread.**
  `POST /ci/api/events/trigger` (`CiEventController`) builds the same `Arrival` from a JSON body, so
  the engine cannot tell a hand-supplied event from a frame — no branch, no flag, no second code
  path, and nothing web-shaped reaching `ci/`. It is the only operation left on that resource — it
  shared it with the push intake until that became a listener. **It demanded `project=*` until
  2026-09-04 and does not any more**: the argument was that an event names no repository, so the
  narrowest honest grant was every project — *because qits-ci could not name a project*. It can, and
  has since the identity cutover: a candidate is a `CiRepoRef` carrying `(repoId, projectId, name)`.
  So a project-scoped machine caller is admitted and its evaluation is narrowed to that project's
  candidates, which refuses a cross-project trigger **by construction** — the other project's
  repository is never asked. `project=*` is unchanged; a project this instance can place no
  repository in is a 403 (`NoRepositoriesInProject`, thrown by the engine and mapped by the
  controller); a candidate whose project qits-ci cannot name is in no scope at all, so an unreachable
  qits-projects listing narrows a scoped call to nothing rather than widening it to everybody; and
  platform pipelines are not part of a scoped evaluation, since one repository's file acting on the
  whole catalogue is a platform-wide act. **A token with NO project claim is the fifth arm and the
  one the live 403 actually landed on**: qits-idp mints its agent and operator credentials with no
  structured claims at all — measured, a commissioned workspace client's token carries only
  `groups` — so the door asks the other half of what qits-idp does issue and admits such a caller on
  a **platform-wide role** (`qits:system`/`qits:admin`), refusing it without one.
  That is not "absent means wildcard": `MachineAuth.requireClaim` answers "does your claim cover THIS
  TARGET", and absence must never mean yes there; this door asks "what may I evaluate FOR you", a
  question a role can answer. `CiEventController.scopeOf`'s javadoc carries the argument in full. The guard sits on the **machine arm** the way
  `cancelReleaseRequestRuns`'s does, and the method carries the `{qits:admin, qits:system}` pair
  rather than the class's machine-only role, because an operator's forwarded session is this door's
  other real caller. What was refused live, and made the documented re-fire mechanism unusable: a
  commissioned client holding `qits:system` and its own project's claim, 403 here while every other
  `/ci/api` read answered it. The **id default is load-bearing**: a
  fresh random UUID per call, or the dedupe below silently drops every rerun. A caller that passes
  one is opting into the dedupe, which is what makes a bootstrap script idempotent. Both are in
  `README.md` under "Triggering one by hand", and both are pinned by `CiManualTriggerTest`.

  **What it does NOT share is the queue, and that is a fix rather than an inconsistency.** It calls
  `evaluateNow`, which runs the evaluation on the request thread and answers what it did:
  **200** with the run ids it recorded (empty and nothing skipped = "asked everybody, matched none"),
  or **503** when no candidate repository could be read. It used to call `onEvent` and answer 202
  whatever came back.

  **The reason is a property only the bus has.** A frame that is not evaluated stays *owed*: the
  claim rolls back and the next catch-up sweep offers it again. A caller-supplied event rides no bus,
  is on no log and holds no claim — nothing anywhere will ever offer it a second time — so "handed to
  a queue" and "lost" were the same outcome for it. Two ways that queue swallows an event and neither
  says a word about it: it is bounded, so a full one is a `false` the endpoint used to discard; and
  it is one thread, so a worker slow or stuck inside a git-host read holds everything behind it with
  no log line at any level. On **2026-08-10** a bootstrap's release replay was answered 2xx for an
  event that was never evaluated — no run, nothing logged, thirty minutes. `CiEventTriggerServiceTest`
  stages exactly that state (worker wedged, queue refusing) and asserts a manual evaluation still
  records its run.

  **The price is stated rather than hidden:** "one git-host fan-out at a time" is now a statement
  about *bus* traffic, and a manual call fans out beside the worker. That is the right way round —
  the budget exists to keep a burst of machine events from storming the git host, and a manual
  trigger is one request from one person, already bounded by the HTTP worker pool. The call is also
  as long as the evaluation, so `qits.ci.trigger-deadline-seconds` (60) bounds it; candidates not
  reached come back in `repositoriesSkipped` rather than being dropped silently.

  **A skipped repository is not a repository that said no.** `EventTriggerLookup.UNREACHABLE` mixes
  "the git host is down", "the repository is gone" and "it has no `main`" on purpose, so the endpoint
  reports the count it *read* beside the ids it could not, and refuses (503) only when it read none.
  Every candidate silent is a statement about the git host, never about the event — the same rule the
  candidate list and the run queue state for an unreachable host.
- **`signatures()` is `Set.of(ALL)` permanently, and it is not laziness.** The wire set is derived
  only when the connection is opened and **the subscriber does not dial at all when the union is
  empty**, so a listener that answered `Set.of()` until it had read some config would never open the
  stream it would read config over. `"*"` is the seam's documented idiom for exactly this, and the
  cost is that this deployable's subscribe frame is literally `["*"]` —
  `CiEventTriggerCausationTest` reads it off the stub to keep that from being a belief.
- **`selects` is left at its default, and that is a decision with a price.** The durable seam asks a
  listener to narrow with a *pure, cheap* predicate and stores only what it selects. This engine's
  selection is neither: deciding whether an event matches anything means listing `.config/qits/` in
  every candidate repository and parsing each trigger file, over HTTP, against answers that change
  with every push. Putting that in front of the claim would fan out on the dispatch thread — and a
  `selects` that throws leaves the event *owed*, so one unreachable git host would wedge the
  watermark. So this consumer selects everything, and **every event on the bus leaves it a claim
  row**. Bounded rather than unbounded: the sweeper prunes claims the watermark has passed by more
  than `qits.eventstream.prune-horizon`, so the table is the stream/catch-up overlap window and not a
  second copy of the log.
- **Three threads, and each boundary is deliberate.** `onFrame` runs on the bus's websocket worker
  (or the catch-up sweeper's thread), one frame at a time for *every* consumer, so it only enqueues.
  Evaluation runs on its own
  single-threaded `ci-trigger-worker`: not the dispatch thread because it reads the git host once per
  candidate repository, and **not a run's thread** either (once `ci-run-worker`, now a runner's
  `ci-runner-run-<runId>` driver), though that is the obvious reuse — that thread is inside a
  running pipeline for minutes, and an event evaluated when the build ends is
  evaluated against a `main` that has moved. Single-threaded was once a correctness rule (two
  evaluations of one repository raced for one bare cache on disk); with the caches gone it is a
  budget — one fan-out at the git host at a time. The queue is bounded.

  **A full queue is a failure, not a WARN and a shrug**, and that is what `onEvent` returning a
  boolean bought. The engine answers whether it *accepted* the event; a `false` means it was not
  evaluated, which is a statement about this process being busy rather than a verdict about the
  event, so the listener throws and the next catch-up sweep offers it again. **`onEvent` is the bus
  listener's and nothing else's now**: everything that reaches it has a redelivery channel behind
  it, which the manual trigger never had — see that bullet above.

  **That residual window is CLOSED, and the price of leaving it open was measured.** It read: the
  claim commits when the event is ACCEPTED, not when the run row exists, so a crash in the gap
  between the enqueue and the evaluation loses that event — milliseconds, and the deliberate price of
  keeping the git-host fan-out off the dispatch thread. Milliseconds are as wide as a cutover chooses
  to make them: on **2026-09-04** a qits-ci redeploy landed in the gap for three release requests
  created within a second of it, their `ReleaseRequestChanged` claimed by the dying instance, no QA
  run recorded, and under a gate that strictly requires verdicts they hung PENDING until they were
  withdrawn and recreated.

  **What is durable now is the ACCEPTANCE, because the effect could not be made so.** The claim is on
  the eventstream datasource and a run row is on ci's, and one JTA transaction does not take both
  (`Enlisted connection used without active transaction`, the same measurement `ScmReleaseListener`
  already runs under). So `onEvent` writes a `ci_owed_event` row on ci's own
  datasource, in its own transaction, **before** it answers `true` — and `evaluateQuietly` deletes it
  when the evaluation returns. The orderings are exhaustive: die before the row commits and the
  accept answers `false`, the listener throws and the bus re-offers; die after it and the row is the
  record that the event was never evaluated.

  **Empty in a healthy process** — the outbox's property, and the reason a non-empty table is a
  signal rather than a second copy of the log. Two sweeps clear it: `onStart` takes everything (the
  deployment is `update_order: stop-first`, so a row at boot belongs to a process that is gone) on
  its own thread, at `CiRunService.BOOT_SWEEP_PRIORITY`, sharing the run sweep's rung — a run it
  records is `QUEUED`, which the run sweep either announces or ignores, both correct (the rung once
  also kept it behind the in-process executor's container reap, gone in qits-506); and a
  `@Scheduled` tick takes what has been owed longer than `qits.ci.trigger-owed-grace`, because a boot sweep is one attempt and a database that was not
  up yet would otherwise leave the events owed until the next deployment.

  **Re-evaluating is safe by construction rather than by care**: `unique (trigger_event_id, repo_id,
  config_path)` makes a second evaluation of an event that already recorded its runs a no-op — which
  is what lets the ledger be at-least-once. And what it does **not** do is retry an evaluation that
  happened: a sweep settles a row whenever `evaluate` returns, including one that reached no readable
  repository, because that case is the git host's and behaves exactly as a live frame's does. Only a
  throw leaves a row owed.

  A shutdown also drains for five seconds rather than calling `shutdownNow` at once. Not the
  durability mechanism — an evaluation that finishes there writes its `QUEUED` rows, which the
  successor's boot sweep re-enqueues exactly as it does an interrupted run's.
- **The causation edge crosses those threads as data, not as context.** `CausationScope` is a plain
  `ThreadLocal` and does not follow work — that is its design, not its limitation. So the frame's
  `id` is written to `ci_run.trigger_event_id`, read back off the row at `announceRun`, and passed to
  `publish(event, parent)` as an explicit argument, which outranks the ambient context precisely for
  this case. It survives a restart, which no context could. Note it is the frame's `id` and never its
  `parentId`: the arriving event causes this run, its own parent is the previous hop's business.
- **The dedupe is a database constraint and the `NULL` behaviour is load-bearing.** `unique
  (trigger_event_id, repo_id, config_path)` is the at-most-one-run-per-(event, trigger file)
  guarantee, and it has to be a constraint rather than a check because what it survives is a race and
  a restart. **It covers pushes too**, since a push run is named by the `SCMPublishCommit` that
  announced it — one announced push, one run. What carries a null there is a run nothing announced
  (`CiRunService.execute`, the test entry), and a database treating two nulls as duplicates would
  make the second such row fail to insert — SQL says rows collide only when all corresponding values
  are non-null and equal, and `CiEventTriggerDedupeTest` pins that the database agrees rather than
  trusting it — plain
  `unique`, never postgres' `nulls not distinct`, which is exactly what must not be asked for. The
  constraint
  kills replays, not descendants, which is why it is **no loop guard**: see the footgun in
  `README.md`, and note that nothing here is built that the future DAG feature would have to undo.

  **It fires at accept now**, since the run row is written by `onEventTrigger` before it returns
  rather than by the executing thread later. Nothing about the semantics moved with it: a redelivery still hits
  the constraint and is still dropped as already-triggered, just on `ci-trigger-worker` instead of
  the run's thread, and before a queue slot is spent rather than after. The retired push intake
  reached the same constraint on the bus's dispatch thread and answered a duplicate the same way:
  null, and an INFO saying the first run stands.

  **The durable claim sits above it and neither replaces the other.** A `consumed_event` row makes
  one event reach the engine at most once, so the constraint fires far less often than it used to —
  but it stays, because it is on ci's *own* datasource and the claim is not, and what it survives is
  a race between two evaluations and a restart mid-evaluation. Two nets, one of which is transactional
  with the run row. Deleting either for tidiness trades a guarantee for a diagram.
- **There is a SECOND collapse on this path and it is not that one.** `CiRunService.supersedeByVersion`
  is `supersedeByCheckoutBranch`'s sibling, down to the columns it writes, and it exists because
  **`SCMPublishTag` is announced once per tag ref of a push**. The publisher is right to emit all of them — a tag is a fact and qits-projects' backup
  consumer needs every one — so a trigger file declaring `event: SCMPublishTag` would get one run per
  tag, four of five building a version nobody asked for. The collapse therefore belongs to the
  consumer that turns a fact into work.

  **Nothing had to be widened for the tag event to be selectable.** `CiEventTriggerListener` says
  `"*"` permanently and the engine matches a trigger file's `event:` against the arriving name as a
  string, so a repository could always name it; what was missing was only the dedupe. `signatures()`
  is not a list anybody adds an event to.

  Four things about it. It runs **inside `acceptEventRun`'s transaction, after the flush**, so a
  superseded row and the row that superseded it commit together. It touches **`QUEUED` rows only**,
  which makes it best-effort by design — a lower tag already running keeps running, because
  cancelling a build to save time it has already spent is the worse trade, and what is guaranteed is
  convergence rather than minimality. The loser **may be the run being accepted**, since a fan-out
  arrives in no order; its row stays as the record that the tag was announced and no reservation
  takes it, which is the path a cancelled queued run already takes. And an **unreadable tag
  supersedes nothing** while an **equal one supersedes** — a failure to compare is not a lower
  version, and a tag that moved is the same case a second push to a branch is.

  **`VersionSort` is hand-rolled and `TAG_EVENT_NAME`/`TAG_NAME_FIELD` are strings**, both for rules
  already on this page: no dependency the native-image builder has to be told about, and `ci/`
  names no other context's types. The strings are the one thing a compiler cannot check, so
  `bus/ScmPublishTagContractTest` resolves them against the real `SCMPublishTag` in the module that
  has the jar — the same guard shape `EventWireReflectionTest` puts over the mix-in's class name.
  Rename the event or the field in qits-githost and the suite goes red, rather than the supersede
  quietly ceasing to fire.
- **`ReleaseRequestChanged` is a trigger this engine needed no code to accept, and exactly one
  column to serve.** The platform runs no CI outside release requests: qits-projects folds a
  request's sources onto `release/<id>` and announces every successful re-fold, and a repository's
  single QA pipeline — the composed `release-request:` slot of `.config/qits/release.yml`, which is
  the only shape left: the hand-written `ci-event-release-request.yml` and its `docs/` template both
  retired with the migration that finished 2026-09-17 — selects it with
  `checkout: { branch: backingBranch, sha: mergedSha }`. Matching, selection and checkout are the
  generic grammar — the branch that gets built is a branch nobody pushed, which is the whole reason
  the event has to exist. "Decide at main, build at the payload's commit" answers the *committed*
  trigger files unchanged; the composed slots are decided at the payload's commit too, since that is
  where `release.yml` is now read from — see "Release slots" below.

  **What is event-specific is THREE accept-time reads, and they follow one rule.** `mergedSha` names
  one fold and the next re-fold replaces it, so it is not a handle a cancellation or a retry can
  hold; the request id is, and `ci_run.release_request_id` is what carries it. `ci_run.priority` and
  `ci_run.downstream_repos` joined it on 2026-09-07 as the run queue's two ordering inputs (see "The
  queue is the table"). All three are read at accept in `CiRunService`, all three are **gated on the
  event NAME** the way `supersedeByVersion` is gated on the tag event's — a `releaseRequestId`, a
  `priority` or a `downstreamTechnicalComponents` elsewhere on the bus is some other context's word,
  and a column that reads any field of any payload eventually records something nobody meant. The
  priority's gate is the one that names **two** events, because `SCMRelease` carries the same field;
  the closure's names only this one, since a release run already outranks every release-request run
  categorically. A value longer than its column is recorded as *none* rather than truncated or
  thrown: the run is the point. An absent or non-array closure is *none* silently — absent is the
  ordinary value for as long as the enrichment has not shipped, and a WARN there would be a line per
  release request forever.

  **`RELEASE_REQUEST_EVENT_NAME`/`RELEASE_REQUEST_ID_FIELD`/`PRIORITY_FIELD`/
  `RELEASE_REQUEST_DOWNSTREAM_FIELD` are strings, and there is no jar to make them anything else** —
  qits-projects publishes no vocabulary jar, by its own ruling and for qits-workspaces' measured
  reason. `bus/ReleaseRequestChangedContractTest` is the guard, and it is `ScmReleaseContractTest`'s
  mechanism rather than `ScmPublishTagContractTest`'s: a **transcription** of the published record's
  component list, run through the real `CanonicalJson`, pinning the name, both checkout dot-paths,
  the id field and both ordering fields. A rename over there is a change to that transcription in the
  same campaign. **The two halves fail differently and the second is the reason the pins were
  added**: renaming the event or a checkout path costs a repository its QA run outright and loudly,
  while renaming an ordering field costs nothing visible at all — an unreadable field is "unknown",
  unknown is a legitimate value with a defined rank, and the only symptom is a queue that has quietly
  stopped being ordered.

  **The re-fold burst needs nothing new.** The backing branch is stable per request, so
  `supersedeByCheckoutBranch` already collapses the queued older folds to the newest tip.

  **Across requests there is a fourth supersede, and it is the one that reaches RUNNING builds**
  (qits-552). `supersedeOtherRequests` runs in both accept paths — `insertEventRun` and
  `insertRetry` — for a `RELEASE_REQUEST`-phase run, and supersedes every other request's unfinished
  run of that phase in the same repository: queued ones in the accepting transaction (`supersede`,
  `dedupe`'s columns with reason `SUPERSEDED_BY_RELEASE_REQUEST`), running ones after the commit
  through `cancel(runId, reason, supersededBy)` (`Accepted.stillRunning`, `cancelSupersededRunning`;
  a 409 from a run that finished in between is skipped). Keyed on `phase`, never on a `release/`
  branch; `RELEASE`, null-phase, other repositories and the same request's runs are never touched.
  It reaches running builds where the collapses do not because two requests' folds are different
  questions competing for one runner, not one question asked twice. `CiReleaseBuildSupersedeTest`.

- **Cancelling and retrying a release request's CI are the two operations that column bought, and
  neither of them touched the dedupe.** `POST /ci/api/runs/cancellations` takes
  `{repoId, releaseRequestId}` and cancels every unfinished run of that pair — **both halves
  required**, since one request folds many repositories and one repository carries many open
  requests, so either alone reaches a sibling's build. It leans entirely on a contract this service
  already had: **a `CANCELLED` run announces nothing**, so a withdrawn request's stopped build can
  never be read by qits-projects' gate as a failure. Nothing in flight is a 202 with an empty list —
  the caller asked for a state, and that state holds — which is what makes it idempotent.

  **The retry is where the interesting decision is.** `POST /ci/api/runs/{runId}/retry` inserts a new
  row copying the source's repository, branch, sha, `release_request_id`, `config_path` and event
  snapshot, so the re-run is the same work and its verdict correlates as the original's would have.
  Every column of `(trigger_event_id, repo_id, config_path)` would therefore be identical, which is
  precisely the replay that constraint refuses — so the retry mints a **synthetic trigger identity**,
  `CiRunService.RETRY_TRIGGER_PREFIX + <its own run id>`. Unique by construction, unmistakably local,
  and the constraint is left exactly as it is. **The tempting alternative was rejected and it is
  worth knowing why**: adding `retry_of_run_id` to the constraint would have put a null in the tuple
  on every ordinary row, and SQL treats such rows as never colliding — the dedupe would have stopped
  firing platform-wide, silently. So `retry_of_run_id` (V9) is pure provenance.

  Two consequences travel with the synthetic id. The **causation edge** is inherited rather than
  re-minted — `causation_id` is copied from the run being re-fired, and `CiRunService.causingEventId`
  is the one place that is read back, for the announcers and for a step's `$QITS_EVENT_ID`, so a
  retried run's `BuildSuccessful` hangs off the domain event that started it rather than off a root
  of its own. A `gating` column used to be re-derived here from `trigger_config` rather than copied,
  because the column on a *finished* run was what that run's verdict had been worth; both the column
  and the concept went with ticket 9441bc6e, and a retry is now worth exactly what the work is worth
  like every other column it copies.

  **`retry_of_run_id` leaves this service on the two terminal build events, and that is what makes a
  retry worth anything downstream.** `BuildSuccessful` and `BuildFailed` carry a `retryOfRunId`
  component, populated off the row by `announceRun`/`announceFailedRun` through `RunAnnouncer`, null
  for every run that is not a retry and therefore an absent key on every ordinary build's canonical
  payload. It had to be its own component because neither of the ids already on the event says it:
  the `trigger_event_id` is the synthetic local token the paragraph above mints, and the causation
  parent is the *original's* inherited cause, so both are silent about which **run** was re-asked.
  The reader is qits-projects, which records one verdict per runId for a fold and reads
  any-red-wins — so without the lineage the re-fire's green lands beside the original's red and the
  release request stays REJECTED forever, which is the loop `qits ci retry` exists to close. What
  qits-ci owes is the fact; superseding is the consumer's own business, exactly as with every other
  component on those events. `BuildStatusChanged` deliberately does **not** carry it: that event is
  a statement about the row for a listing mirror, and a mirror supersedes nothing.

  Only a **terminal** run is retryable (409 otherwise): two runs racing for one verdict is not what
  was asked for. Cancelled runs are retryable, which is the point — a re-fire is what a cancellation
  invites.
- **Every step gates, and `gating:` is a parse error at both scopes.** A file and a step could each
  declare it, the run's announced verdict was the two ANDed, and that is what let
  `ci-event-build.yml` and `ci-event-userflows.yml` become one file. The two-files-into-one story
  survives; the flag does not (ticket 9441bc6e). QA that does not gate is pointless, and it did not
  merely fail to gate: a run whose gating step passed and whose non-gating step went red announced
  no `BuildSuccessful` at all, only a `BuildFailed{gating:false}` qits-projects' release gate could
  neither accept nor refuse, so the request parked forever — measured once at fourteen hours on one
  flaky test.

  Two rules replace it and both were already true underneath it. **Non-blocking work does not belong
  in a release-request pipeline at all**, which is a placement decision rather than a flag; and **the
  steps that must run first go first**, because everything after a failure is `SKIPPED`. That
  ordering rule is what the merged file really bought, and it is unchanged.

  It is a **parse error** rather than an ignored key, and at the step scope that matters more rather
  than less: unknown per-step keys are lenient, so deleting the declaration and leaving nothing
  behind would have made every repository's committed `gating: false` quietly read as nothing —
  which is exactly how the 2026-09-07 retirement of this same flag came back. `branches:` is the
  precedent. The one text allowed to still carry the key is a run's **stored snapshot**; see
  `CiEventTriggerParser.parseSnapshot`, whose javadoc argues the asymmetry in full.
- **The trigger file is strict about unknown TOP-LEVEL keys and lenient about unknown per-step ones**,
  and the asymmetry is the point rather than an inconsistency. In a pipeline an unread key costs a
  feature that was not there yet; in a *selection* it costs correctness, because an absent `when:`
  means **unconditional** — so a mistyped `wehn:` would silently widen the trigger to every event of
  that name. Duplicate keys are errors for the same reason (a silently dropped condition widens),
  which is why `CiConfigSchema.load` takes a `strictDuplicateKeys` flag at all: the lenient caller was
  `CiConfigParser`, which retired with per-push CI on 2026-09-05, and the flag survives it because it
  is the argument rather than the caller that is worth keeping.
- **The per-step `branches:` filter went with the push path too, and its REFUSAL stayed.** A step
  could bind itself to the run's branch while there was a pipeline file whose branch was the push's;
  it was always a parse error in a trigger file, because an event run's branch is the trigger's
  single decision made before any step exists, so a filter over it is inert decoration or a step that
  can never run. With no other file kind left, `CiPipeline.BranchFilter`, the record component and
  the SKIPPED-by-branch arm in `runSteps` are deleted — but `CiConfigSchema.steps` still refuses the
  key by name, so a repository that carries one is told rather than having it ignored. `SKIPPED`
  therefore means exactly one thing again: the loop never reached this step.
- **`artifacts:` is the one key the trigger file adds rather than subtracts**, and it is what makes a
  file a *release pipeline*: a non-empty list of `{type: npm|maven|docker|daemon, name: …}`, strict in every
  direction (empty list, unknown type, blank name, extra key, wrong shape — all parse errors naming
  the file). It was a parse error in `ci-post-receive.yml` for its own reason: what a declaration
  announces is the *triggering* event's version, and a push carries none, so the key could only ever
  have been inert there. The declaration is a **claim**, never an
  observation — qits-ci cannot see what a step pushed — and it is declared rather than emitted
  because a declaration is statically readable, which is the whole of what the parked cycle-detection
  work needs. The daemon's return channel could not have carried an emission anyway: it is
  `StepChunk` and `StepFinished`, and a stdout sentinel is forbidden by design.
- **The candidate list was the feature's one acknowledged compromise, and it is the worked example of
  a seam paying for itself.** It read: qits-artifacts exposes no listing, so `KnownCiRepos` answers
  with what qits-ci already knows — recorded runs' repo ids — and a
  repository qits-ci has recorded no run for cannot event-trigger. That cost was real: it blocked
  bootstrapping a platform seeded straight onto the git host, which is exactly what `POST
  /ci/api/events/trigger` exists for. The git host has since grown the listing, the swap was the one
  class the javadoc promised, and nothing in the engine moved.

  **What ships now is a union, and union is the design rather than a step towards replacement.**
  `ListedAndKnownCiRepos` is the bean the engine gets: the platform's catalogue **added to**
  `KnownCiRepos`' answer.

  **Which catalogue is a config kill switch, and the candidate unit is a `CiRepoRef`.** With
  `qits.ci.projects-url` set it is qits-projects' `GET /projects/api/repositories` →
  `{"repositories":[{id, projectId, name, mainBranch}]}` through the `CiRepositoryListing` port
  (`service/…/projects/HttpProjectsRepoListing`) — the only listing that can answer a public NAME,
  which after the identity cutover is the only thing a trigger file can select on or a content read
  can be addressed by. With the key unset it is the git host's own `GET <qits.ci.git-host-url>/git`
  through `GitHostRepoListing`, which is what this service always used and what keeps a clone-alone
  build and a pre-cutover platform working. Never both: a configured qits-projects is the authority
  on which repositories exist, and adding UUIDs under it would put candidates nothing can address
  back into the set. An entry with no `name` is **skipped** — no public address means no trigger file
  to read — and a named entry beats the known set's id-only one for the same repository.
  The listing is one HTTP call away, so an unreachable host, a non-200, a body that is not JSON and a
  body with no `repositories` array are each one WARN naming the url and an empty contribution — the
  answer is then the known set alone, which is precisely what shipped before. **A read failure must
  never shrink the candidate set**, the same rule the run queue states for an unreachable git host one
  section up. The two halves also age in opposite directions: the listing is what the host has now,
  the known set still covers a repository the host has stopped listing but ci holds a row for.

  Four things about the HTTP half, which is `service/…/githost/HttpGitHostRepoListing` and is in
  `service/` because **`ci/` stays free of `java.net.http`** — the rule `CiConfigSource` set and
  every port here follows:

  - **The url is derived, never configured.** It is the git host's own base plus the same `/git`
    segment `HttpGitConfigSource` reads content under, so the listing and the config read move
    together.
  - **The timeouts are short because of which thread this is on.** 2s connect, 3s for the whole
    exchange. It runs on the single-threaded `ci-trigger-worker` in front of every evaluation, so an
    untimed call would stall *every* arriving event; and the evaluation it precedes reads the host
    per candidate, so the listing must never be the slow part. Past the deadline the known set is a
    correct answer.
  - **The cache is five seconds and deliberately trivial.** One evaluation already costs a read per
    candidate, so this is not an optimisation — it is so a burst on the bus is one listing read rather
    than one per frame. Only a *successful* read is cached, because caching a failure would keep a git
    host that came back up invisible for the window.
  - **A non-HTTP git host is a DEBUG, not a WARN.** A value that is not an HTTP url serves no
    listing and never could; warning on it would be a line per event forever, which is the same
    argument `HttpGitConfigSource` makes for keeping the trigger-listing path's failures at DEBUG —
    that path asks every known repository on every frame, so one deleted repository would otherwise
    cost a warning per green build forever. Ids off the listing are filtered through `CiIdentifiers`
    before they reach a url.

  `KnownCiRepos` stays `@DefaultBean` and `ListedAndKnownCiRepos` is an ordinary bean, which is the
  whole of the CDI arrangement: the ordinary bean wins the engine's injection point while the default
  one stays injectable by its own type, and a `@Mock` alternative outranks both — so
  `FakeCandidateRepos` still replaces the seam whole and still exercises the swap it was written for.
- **Nothing here needed native-image registration**, and `EventWireReflection`'s javadoc says why in
  full: SnakeYAML's `SafeConstructor` produces plain collections, the parser builds its records by
  hand, and the payload is `readTree`'d into a `JsonNode` and walked. No binding, no reflection, no
  fifth member of the family this file names. Check that reasoning again if the engine ever gains a
  Jackson `readValue`.

## Platform pipelines

A second source of trigger files, and the whole of what is new about it is *which repository the run
is about*.

- **Packaged, like the archetypes — but as this module's own resources, not a copy out of
  `.config/qits/`.** `ci/src/main/resources/platform-pipelines/*.yml` in this repository, carried
  onto the jar's classpath as `platform-pipelines/<name>.yml` by `src/main/resources`'s ordinary,
  unfiltered declaration, and read once by `CiPlatformPipelines` (boot fails when one is missing;
  the native image names them in `quarkus.native.resources.includes`). Parsed by the same
  `CiEventTriggerParser`. Until 2026-10-02 they were the wrapper's `ci-platform-event-*.yml`, listed
  at its `main` per event; `qits.ci.platform-pipelines-repository` is retired and only logged as
  ignored. They moved out of `.config/qits/` in qits-1077: that directory is a repository's own
  configuration, gated by qits-projects' `ApprovalPolicy` for a person's approval, while these files
  are platform code and belong beside the Java that reads them.
- **The payload names the repository, and it must be in the catalogue.** The run is recorded against,
  and its steps clone, the repository `payload.repository` names, resolved against the same candidate
  list the evaluation already has (name first, storage id second — the pre-cutover arm). No field, no
  such repository, or a repository that could not be read this evaluation: one WARN naming the event
  and the repository, and no run. **A read failure is not a run**, the same rule the rest of this
  engine states.
- **The head comes from the candidate pass, not from a second read.** `evaluate` keeps the head each
  candidate answered with and the platform pass looks the target's up in it. So a platform pipeline
  costs no read at all — and a target the
  evaluation could not read has no head, which is exactly the case that must not become a run.
- **Automation kind files are the second packaged source.** `platform-pipelines/automations/<kind>.yml`
  declares `image`, `timeout-seconds`, `script` and optionally `qits-cli: true` (only `true`: any
  other value fails the boot), nothing else;
  `CiAutomationComposer` composes it into a `ReleaseRequestAutomation` trigger selecting `kind:
  <kind>` with the platform's prelude (payload refusals, fold fetch, `superseded before start`) and
  postlude (stage only `commitPaths`, the `--ignore-submodules=none` guard, plain push). The set is
  `CiPlatformPipelines.AUTOMATIONS`, held to the files by `PackagedPlatformPipelinesTest`; a refused
  kind file fails the boot. `quarkus.native.resources.includes` names the
  `automations/` subdirectory explicitly, because a `*` does not reach into it. `qits-cli: true`
  spends `CiReleaseComposer.cliFetch` in its `AUTOMATION` form — one download text for the release
  prelude, the QA report hook and the automations, never a copy — and that form is hard: a kind that
  asked for the CLI cannot run without it. A packaged pipeline may say the same with a top-level
  `qits-cli: true` (qits-893, `maintenance-bump.yml`, whose commit message is `qits changelog
  bump-message`'s): `CiPlatformPipelines.withQitsCli` strips the line before the strict trigger
  parser sees it and prepends the same `AUTOMATION` fetch to every `script: |` block, then checks
  every parsed step starts with it, failing the boot otherwise.
- **Two files are two runs, deliberately.** A repository carrying both a local and a platform trigger
  for one event gets two rows: the dedupe is `(trigger_event_id, repo_id, config_path)` and the paths
  differ. That is also how a run says which kind it was — `config_path` already travels to the API,
  and `ci/src/main/resources/platform-pipelines/` is the answer. Nothing was added to the schema for this.
- **Blank is off and reads nothing.** The key is injected as `Optional<String>`, because a property
  spelled as the empty string arrives as *absent* and a bare `String` injection point fails the whole
  deployment on the one value that means "off". The suites turn it off in their
  `application.properties` and arm it per test through the package-private setter — a method, not a
  field write, because this bean is normal-scoped.
- **Nothing about the thread discipline moved.** The platform pass runs inside the same
  `evaluate`, on `ci-trigger-worker` or on the manual trigger's own request thread, inside the same
  try/catch that keeps one failure from costing the others theirs, and never throws out of the
  evaluation.

## Release slots: the release cycle as configuration

A **third** source of pipelines, and unlike the two above it is not a trigger file at all.
`.config/qits/release.yml` declares *slots*; `CiReleaseComposer` compiles them plus an archetype
recipe into two ordinary trigger documents at evaluation time, and everything downstream — the parser, the
checkout resolution, the run row, the dedupe, restart-reparse — is the path a committed file already
takes. `README.md` under "The fourth file" has the format and the rollout story; what follows is what
biting it feels like.

**Its two keys are PHASES, and this is where that vocabulary is fixed.** `release-request:` is phase
one — QA, a run at `release/<id>@mergedSha` — and `release:` is phase two, publish, a run at
`<version>@commitSha`; phase three is the deploy, which is qits-deployments' own release request and
appears in no slot file. A phase is a unit of work with a state and a rerun, a **gate** is the
condition between two phases (CI, approval, publish, deployment), and **a gate delays, it does not
fail**. **Nothing in the composed text names a phase**: the DSL declares slots, and which phase a run
is, is the engine's word, taken from the triggering event and never from which config file produced
the document — which is what made a migrated and an unmigrated repository's runs read the same
while the fleet was converting, and what still keeps this feature out of every reader keyed on a
phase.

- **Four classes, and the split is the usual one.** `CiReleaseSlotParser` and `CiReleaseSlots` are
  the document; `CiReleaseArchetypes` resolves a recipe — the repository's own copy through the
  existing `CiConfigSource` port, otherwise the one packaged into this jar; `CiReleaseComposer` is a **pure function** — three arguments in, two strings out, no clock,
  no config, no lookup and no logging. That purity is what makes the golden-file tests worth having:
  a change to the platform prelude is a change to 47 repositories' behaviour, and the only way it
  stays reviewable is if the diff is the composed text itself. `ci/src/test/resources/composed/` is
  where those live, and a missing golden writes the produced document to `target/composed/` and fails
  naming both paths rather than offering an `-Dupdate.goldens` that would let the test agree with
  itself.
- **`CiConfigSource` grew a fourth answer, `readFile`, and its three statuses are load-bearing —
  and the direction the engine reads them in INVERTED when the fleet finished migrating.** `ABSENT`
  is a 404 at a rev the host has already resolved: the repository declares no release cycle, which is
  final. `UNREACHABLE` is a blip. Both used to fall back to the hand-written trigger pair, on the
  argument that a migrated repository had no such file for the fallback to find and so paid nothing.
  There is no pair anywhere now, so that reading answers "this repository declares no QA" about a
  release request that is waiting for exactly that QA's verdict — and settles the owed row, hanging
  it PENDING forever with nothing to re-drive it. So `UNREACHABLE` is now its own outcome all the way
  out: `ReleaseSlots.UNREADABLE` → `Evaluation.repositoriesUnreadable` → **the event is not settled**,
  and the owed-event sweep evaluates it again. The look for a repository's own archetype recipe is
  the same read of the same repository and has the same three answers: `UNREACHABLE` is owed,
  `ABSENT` means the packaged recipe answers. `ABSENT` and every failure of committed BYTES (unknown
  archetype, unparseable slot file, uncomposable pair) are no run and ARE settled — a person's
  declaration is final, and re-asking a git host cannot change it. `CiReleaseSlotTriggerTest` holds
  the contrast from both sides.
- **The extra read is gated on the two release event names.** `ReleaseRequestChanged` and
  `SCMRelease` and nothing else, so a `BuildSuccessful` costs precisely what it cost before this
  feature existed. `CiReleaseSlotTriggerTest` asserts the *absence* of the read for an ordinary event.
- **An archetype is resolved in two steps and no other repository is read for one.** First
  `.config/qits/release-archetypes/<name>.yml` in the repository the run is for, through
  `CiConfigSource.readFile` at the revision its `release.yml` was read at; otherwise the classpath
  resource `release-archetypes/<name>.yml`, which `ci/pom.xml` packages into this jar from this
  repository's own `.config/qits/release-archetypes/`. Platform pipelines are packaged too, but not
  the same way: they are this module's own `ci/src/main/resources/platform-pipelines/*.yml`, carried
  onto the classpath by `src/main/resources`'s ordinary declaration rather than copied out of
  `.config/qits/` (`CiPlatformPipelines`) — so, as with the archetype, no repository is read for one.
  Until qits-583 the recipe was read from that repository at its newest released tag, which made a
  recipe fix ship only with a wrapper release — the last step of a ticket — and meant no CI run ever
  executed a changed recipe before it shipped.
  <br>**Shadowing is the design.** Any repository may carry its own copy of a packaged archetype or
  one of its own invention. It grants nothing a branch could not already do: the platform prelude
  and postlude are `CiReleaseComposer`'s, in Java, and a recipe contributes only slot steps,
  `artifacts:` and `userflows:`, all of which a repository can already replace in `release.yml`.
  <br>**Three answers** (`CiReleaseArchetypes.Status`). `FOUND`. `UNREADABLE` — the local read was
  `UNREACHABLE`: `ComposeOutcome.ARCHETYPE_UNREADABLE`, the event stays owed, `release-phase`
  answers 503, a retry falls back to its snapshot. `UNKNOWN` — the name is in neither place, or the
  local copy does not parse: `ARCHETYPE_UNKNOWN`, no run and the event settled, `release-phase`
  answers `declared: true`. **Neither local failure falls through to the packaged copy**: a broken
  shadow silently becoming the platform recipe is a pipeline its author replaced running green, and
  a blip must not decide which of two pipelines a commit gets.
  <br>**This repository's own release request gates one recipe of eight.** qits-ci-service is a
  `java-service`, so its QA run reads `java-service.yml` locally at its fold and executes it.
  `PackagedReleaseArchetypesTest` is the cover for the other seven: each is on the classpath
  byte-for-byte (not filtered), parses, declares a `release-request:` slot (qits-projects arms the
  CI gate on the mere presence of `archetype:`), composes, and every script passes a shell `-n`.
  <br>**The native image bundles them only because it is told to**:
  `quarkus.native.resources.includes` in `service`'s `application.properties`. No JVM test can see
  that key missing, and the symptom in the binary would be every unshadowed `archetype:` answering
  "no such archetype" — no run, settled, one WARN. **So the binary refuses to boot without them**:
  `CiReleaseArchetypes.requirePackaged`, a `StartupEvent` observer in every launch mode (test
  included), reads and parses the eight names in `REQUIRED_PACKAGED` and throws naming the missing
  ones, which fails the health gate and keeps the previous container. That set is what this build
  must carry and never an allow-list for `read`; `PackagedReleaseArchetypesTest` holds it equal to
  the files in `.config/qits/release-archetypes/`.
- **The pipeline that gates a revision is read FROM that revision.** A candidate's `release.yml`
  is read at the commit the arriving release event is about — the request's fold (`payload.mergedSha`) for a `ReleaseRequestChanged`, the released
  tag's commit (`payload.commitSha`) for an `SCMRelease` — which is the same commit the composed run
  checks out, resolved by `CiEventTriggerService.releaseRev` through the same payload paths
  `CiReleaseComposer` emits into the composed `checkout:`. It used to be read at `main`'s head, and
  that broke in two directions: a repository could never ship its own FIRST `release.yml` (the tag
  declared a `release:` slot, qits-projects' gate stamped the request publish-gated from that tag —
  `releasePhaseAt` has always read at the rev it was asked about — and the run composed from a `main`
  with no such file, so no run was recorded, the event settled and the request sat RELEASED forever
  with `main` unable to move; measured 2026-09-22 on qits-landing-app), and no change to
  `release.yml` was ever exercised by the release that carried it. **An archetype that file names
  is looked for at that same revision**, so a declaration and a local recipe are one commit's
  bytes. **A payload with no usable sha composes NOTHING — there is no fallback to `main`'s head
  any more.** It used to answer the head, on the argument that the
  composed `optional:` checkout would build the head anyway; that justified the defect with the
  defect, since a pipeline composed from `main` gates a commit nobody released. It is also
  unreachable by construction: qits-projects announces a release request from the fold path
  alone, and a request whose fold could not be made is CONFLICTED — frozen, never re-folded,
  never re-announced until a push clears it — so nothing live emits a release event with no
  revision. So it is reported as the broken invariant it is (one ERROR naming the event, its
  name and the field), no file is read, no run is recorded, and the event is **settled** rather
  than left owed: a payload cannot grow a field afterwards, so an owed row for it is a row
  nothing could ever clear, with the watermark stuck behind it. `CiReleaseSlotTriggerTest`
  asserts the absences — never the repository's file at `main`, never a recipe from the
  platform-pipelines repository, with readable decoys seeded at every revision the old reads used
  so a regression composes successfully — and the missing and the malformed sha as two more.
  <br>**`ci_run` records what was used** — `archetype_name`, `archetype_config_path`,
  `archetype_rev` (`V20__run_archetype.sql`) and `archetype_version`
  (`V22__run_archetype_version.sql`), nullable, no backfill. A **local** recipe records the revision
  it was read at in `archetype_rev` (the run's own `commit_sha`) and a null version; a **packaged**
  one records a null rev and this qits-ci-service's own version (`quarkus.application.version`) —
  so `archetype_rev` non-null means "shadowed locally", and two rows with differing
  `archetype_version` were composed by two qits-ci releases. All four null is three different
  legitimate statements and never "unknown for this run": a committed trigger file composed from
  nothing, a slot file naming no `archetype:`, or a row older than the columns. **A retry records
  its own re-composition rather than copying the source row's** — the one arm that copies is a
  retry that fell back to the stored document, since those bytes are what will really run.
- **A composed document supersedes nothing, and the rule that said otherwise is gone.** While the
  fleet was migrating, a present `release.yml` skipped a still-committed
  `ci-event-release-request.yml`/`ci-event-release.yml` with a WARN naming both paths, so the two
  could not fire beside each other for one release. Zero repositories carry either file and the
  supersession, both path constants and the fallback were deleted on 2026-09-18. Every
  `ci-event-*.yml` now evaluates beside the composed documents under the ordinary "two files, two
  declared pipelines, two runs" rule.
- **`ci/` gained no HTTP and no new dependency.** The composer emits strings; the git-host reads
  are the port's and the packaged recipes are a plain `getResourceAsStream`. There is no migration, no bus change, no endpoint and nothing in `qits-ci-daemon`.
- **A RETRY of a composed run re-composes the platform's half, and that is the one place a run's
  stored snapshot is not replayed verbatim.** The document on such a row is half the repository's
  (its declared script) and half qits-ci's (the prelude and the postlude around it), and the platform
  half is *environment*: a fix to it is a fix to 47 repositories at once. Measured 2026-09-13 on
  qits-coding-agents, whose failed publish, retried hours after the prelude was fixed, would have
  re-run the broken prelude and died at the same SBOM step — so a platform fix could never heal an
  earlier failed release by retry, which is exactly the loop "fix the environment, `qits ci retry`,
  the request finalizes" depends on. `CiRunService.retriedPipeline` therefore calls
  `CiEventTriggerService.recomposedReleaseDocument` when `config_path` is
  `CiReleaseSlotParser.CONFIG_PATH`, and three things about it are load-bearing. The slot file is read
  at the run's **own commit**, never at its branch: the retry builds that commit, and for a publish
  run it is a released tag whose bytes cannot move — so the repository's share of the document is
  identical by construction and only the platform's changes. A **local** archetype is part of that
  share and is read again at the same commit; a **packaged** one is the retrying qits-ci's, which
  is what lets a recipe fix heal an earlier failure by retry once the qits-ci carrying it is
  deployed. Every way the re-composition can fail —
  the ref is unreadable, `release.yml` is gone from it, the archetype it names cannot be looked for
  or exists nowhere, compose throws — **falls back to the stored snapshot** with a WARN naming the reason, because a retry that refused
  is worse than a retry of the old document. And the reads happen **outside** `DbRetry.inNewTx`, like
  every other IO on this class's write paths. A run from a hand-written `ci-event-*.yml` has no
  platform half and is replayed byte for byte, as it always was; the predicted step durations are
  then derived from whichever document the retry really ends up with.
- **The qits CLI a composed release step runs is a POM PIN, not a resolution.** The prelude
  downloads `$QITS_ARTIFACTS_CLI_PACKAGE` at `$QITS_ARTIFACTS_CLI_VERSION` from
  `https://registry.qits.$QITS_DOMAIN/artifacts/daemons/` — the store's public name as code, never a
  URL variable (`CiReleaseComposer.CLI_DOWNLOAD_BASE`, qits-731) — and the version comes
  from `eu.wohlben.qits:qits-platform-access-cli-binary` — one class, three strings, no bytes of the
  binary — by way of `StepContainerSettings`. It used to read qits-artifacts' own daemons listing and
  take that package's `latestVersion` at every step start, which made one CLI release a shared,
  unversioned, unreviewed input to every release on the platform at once: on 2026-09-13 one bad CLI
  broke all of them, with nothing changed in any consumer's tree and no line anybody could revert.
  Four things follow. The pin has to **resolve** for this reactor to build, so a version that does
  not exist is a red build rather than a broken release later. `QitsCliPinIT` proves the coordinate
  is really in the store, that the binary at it carries the commands and options the composed
  postlude calls, and that the composed text makes exactly those calls.
  qits-platform-maintenance moves the line like any internal pin and **this repository's own release
  request gates the move** — never hand-edit it to chase a release. And a release step's image no
  longer needs `jq` for the CLI's sake: the listing read, its best-effort bearer and the jq that
  parsed it are all gone. `qits.ci.artifacts-cli-version-override` is an emergency door that ships
  unset and must stay that way; setting it runs a CLI nothing gated.
- **The qits CLI a composed release step runs is a POM PIN, not a resolution.** The prelude
  downloads `$QITS_ARTIFACTS_CLI_PACKAGE` at `$QITS_ARTIFACTS_CLI_VERSION` from
  `https://registry.qits.$QITS_DOMAIN/artifacts/daemons/` — the store's public name as code, never a
  URL variable (`CiReleaseComposer.CLI_DOWNLOAD_BASE`, qits-731) — and the version comes
  from `eu.wohlben.qits:qits-platform-access-cli-binary` — one class, three strings, no bytes of the
  binary — by way of `StepContainerSettings`. It used to read qits-artifacts' own daemons listing and
  take that package's `latestVersion` at every step start, which made one CLI release a shared,
  unversioned, unreviewed input to every release on the platform at once: on 2026-09-13 one bad CLI
  broke all of them, with nothing changed in any consumer's tree and no line anybody could revert.
  Four things follow. The pin has to **resolve** for this reactor to build, so a version that does
  not exist is a red build rather than a broken release later. `QitsCliPinIT` proves the coordinate
  is really in the store, that the binary at it carries the commands and options the composed
  postlude calls, and that the composed text makes exactly those calls.
  qits-platform-maintenance moves the line like any internal pin and **this repository's own release
  request gates the move** — never hand-edit it to chase a release. And a release step's image no
  longer needs `jq` for the CLI's sake: the listing read, its best-effort bearer and the jq that
  parsed it are all gone. `qits.ci.artifacts-cli-version-override` is an emergency door that ships
  unset and must stay that way; setting it runs a CLI nothing gated.
- **An image without bash is a real shape, and the fallback is a RUN-time check.** The composer
  cannot see inside an image, so every dependency the wrapper has on an image's contents is decided
  in the emitted text: `if command -v bash …; then bash -eu …; else sh -eu …; fi` for the declared
  script, and `curl` → `wget -O` → a refusal naming the image for the CLI fetch. qits-build-images-oci
  is the one repository that cannot run on a `qits/build-images/*` — it builds them — and runs on
  upstream `docker:28-dind`, which has `/bin/sh` and `/bin/ash` but no `/bin/bash`, and `wget` but no
  `curl`. A declared script using a bashism fails there, and that is the repository's own business.
  A per-repository flag was rejected twice over: it asks an author to restate a fact about an image
  they did not build, and it is wrong the moment the image changes underneath the declaration. Only
  the CLI *fetch* degrades — the binary is static, so the postlude is unaffected by which arm ran —
  and there is no `jq` fallback to write because the pom pin left no JSON in the text to parse.
- **A lockfile is installed as committed, and the prelude checks where it points** (qits-731).
  Every recipe used to `sed -i` the origins of its lockfile's `resolved` URLs, because lockfiles were
  generated on a host that named internal services. Nothing rewrites one now: every lockfile is
  committed resolving against `https://registry.qits.$QITS_DOMAIN/` (the hosted `@qits` scope) or
  `https://mirror.qits.$QITS_DOMAIN/` (the npmjs cache), and installs run against it as is with
  `npm_config_registry`/`npm_config_@qits:registry` set to those two. The platform half of that is
  the prelude: it demands `$QITS_DOMAIN` first in every composed step, then writes
  `/tmp/qits-lockfile-origins.sh` (`CiReleaseComposer.lockfileOriginCheck`) and runs it before the
  declared script. It `find`s every `package-lock.json` under the checkout — `find` and not `git
  ls-files`, because a submodule's files are tracked by no index the parent can list — skips
  `node_modules`, and fails the step naming the file and up to five entries when a `resolved` value
  carrying `://` starts with neither origin. grep, sed and awk only, because docker:28-dind's busybox
  is the floor. **The prelude runs before the declared script, so a submodule the script materialises
  is not there yet**: java-service's two steps and this repository's `release.yml` run the same file
  again right after their `git submodule update`, guarded on the file existing so a step composed by a
  qits-ci that predates the check still runs. `NoLockfileRewriteTest` holds that no archetype,
  platform pipeline or `release.yml` here carries a `sed -i` over `resolved` again. Platform
  pipelines are not composed and get no prelude, so one that installs from npm has to write the
  same lockfile discipline into its own script by hand — as the retired `screenshot-baselines.yml`
  did (qits-1007), and as the `screenshot-baselines` automation kind file still does.
- **The heredoc is the security-shaped part.** A repository's script is data: quoted heredoc to
  `/tmp/qits-slot.sh`, run as a child shell under `-eu`. The only way out of a quoted heredoc is a line
  carrying the delimiter, so a script containing `QITS_SLOT_EOF` is a `CiConfigException` naming the
  file the script came from — the repository's, or the archetype's, whichever really wrote it. Note
  this is not a new execution path: qits-ci still executes nothing, and the composed text leaves this
  process as the same `script` field of the same frame it always did.
- **Interpolation is three values and they are charset-guarded at PARSE time.** An artifact's `type`
  (an enum), its `name`, and its `sbom:` path, held to an allow-list rather than an escape. The
  composer single-quotes them as well; the guard is what makes the quoting a second line of defence
  rather than the only one. (`publish:` is not a fourth: it is an enum, it reaches the composed
  `artifacts:` block and never a script except as the decision the postlude makes.)
- **`publish: if-changed` is the one artifact declaration that is checked rather than believed
  (qits-620).** `maven` and `npm` entries only — on `docker`, `daemon` or `docs` the key is a parse
  error naming the entry, in `CiReleaseSlotParser` and in `CiEventTriggerParser` alike, through the
  one `CiArtifact.requirePublish` — and `always` is the default. It rides on `CiArtifact`, so the
  composer emits it into the composed `artifacts:` block (only when it is not the default), the
  trigger parser reads it back, a restart reparses it off the snapshot, and `ReleaseJoin.owe` writes
  it onto the owed row (`ci_release_announcement.publish`, V30). Before announcing such a row the
  join asks the `CiArtifactPresence` port — `service/…/registry/HttpArtifactPresence`, one `GET` of
  the version's `.pom` or of the npm packument at the origin `HttpImagePins` already derives
  (`registry/ArtifactsOrigin`: `qits.artifacts.url`, else the origin of
  `qits.artifacts.maven.registry-url`; no credential, reads are unguarded) — up to three times on an
  inconclusive answer, **outside** the locking transaction. Absent is followed by the newest-version
  question (below, under V30), an unchanged row is an INFO, still-inconclusive an ERROR, and every
  outcome settles the row (`announced_at` + `skip_reason`) so no re-drive asks or announces again.
  **The drop is deliberate and must not be "fixed" into an announcement**: qits-maintenance's daily
  scan moves `mt_latest` to the version the store really holds, so a missed announcement costs at
  most a day, while announcing an unverified one offers a version that may not exist.
  <br>**`announce: if-published` (qits-561) was this bullet until qits-648 deleted it.** It was the
  first checked declaration, for a repository whose own steps published conditionally; release B of
  qits-640 kept such entries self-published (no upload, no SBOM submit, `path:`/`link:` refused)
  while the three adopters moved to `contracts:`, and qits-648 removed the key, that transitional
  path and `isIfPublished` together. `announce:` is now an unknown key in both parsers, refused with
  a message naming `publish:` as its replacement (`CiArtifact.retiredKeyHint`). V29's `announce`
  column stays, unwritten — an applied migration is never edited — and the join reads it only so a
  row owed before the deletion folds into `isIfChanged` and keeps its store check.
- **`GET /ci/api/repositories/{repoId}/release-phase?rev=` is the one endpoint this feature grew, and
  it exists because only the composer can answer.** qits-projects decides whether a released tag is
  publish-gated, and it decided by reading that tag's `release.yml` and asking whether it named an
  `archetype:` — answerable from the file, and the wrong question: `spa-frontend` and `cli` declare
  no `release:` slot, so a migrated SPA got a PUBLISH gate whose run nobody would ever record and its
  request sat RELEASED forever. What decides is the **composition**, because the whole-slot override
  lets a repository declare its own `release:` on top of a publish-free archetype. So qits-ci
  answers: `CiEventTriggerService.releasePhaseAt` composes at the rev and reports
  `DECLARED`/`NOT_DECLARED`/`UNKNOWN`, and `CiRepositoryController` maps the third to **503**.
  **Since qits-893 every composition has a release half** — one with no `release:` slot gets a
  synthesised changelog-only step (`CiReleaseComposer.changelogOnlySlot`) — so an SPA or a CLI now
  answers `DECLARED` and `NOT_DECLARED` is left to a rev with no `release.yml` at all.
  <br>**Three answers, because a boolean has to give a failure a side and both sides are wrong.** A
  `false` derived from a read that did not happen publishes a release nothing gated; a `true` derived
  from one hangs a request behind a gate nobody can answer. So `UNKNOWN` is reserved for the question
  not having been asked at all — the repository is in no catalogue here, the slot file's read was
  `UNREACHABLE`, or the look for the repository's own copy of the archetype was — and the caller
  retries. **A slot file that will not parse, an archetype that exists neither locally nor packaged
  (or whose local copy is broken), and a pair that will not compile, answer `declared: true`**, the
  second with a `detail` naming the archetype: those are the repository's
  own committed bytes, the fix is a commit, and waiting is recoverable where publishing past an
  unchecked pipeline is not. `detail` says which case it was, and it is contract rather than log.
  <br>`attemptCompose` is the composition with the outcome named — extracted rather than copied,
  because the evaluation and the retry want a null and a WARN while this one needs "the file is
  broken" and "the git host could not be asked" to be opposite answers. A packaged archetype is the
  asked qits-ci's own, so for a repository on one the answer is about the pipeline as it composes
  now; that is the wanted direction, since the run that would satisfy the gate would be composed
  now too. An unknown archetype was `UNKNOWN` — a 503 retried forever — until qits-583, while "not
  there" could still mean "not released yet". The endpoint is in
  `docs/openapi.yml` for `GET /ci/api/daemon`'s reason — a machine consumer whose contract is written
  down here and nowhere else.
- **`POST /ci/api/repositories/{repoId}/release-composition?rev=` was the door beside it and is
  GONE.** It composed a candidate `release.yml` at a rev and reported it per phase and per side
  against the two hand-written trigger files that rev committed, for a person about to write a
  migration commit. Both halves of the question retired with the split pipeline on 2026-09-18: there
  is no committed pair to hold a candidate against, and nobody is writing a migration commit. It is
  mentioned here only so that a reader who finds it in the log knows it was removed rather than
  moved; the summariser stack it needed (`phase`, `described`, `summarise`, the script digests) and
  `ComposeAttempt.declaredArtifacts`, which existed for it alone, went with it.
- **`userflows:` composes no step, deliberately.** It replaces qits-projects' substring grep for
  `@userflows/<site>` in a QA recipe — a search inside a shell script, which stops working the moment
  the script is composed — so it is a declaration for the reader on the other side of the release.
  How a bundle is built and uploaded is the archetype's business, and inventing a step here would be
  a guess the recipes have to undo.

## Adding a dependency on another context

Don't. Things arrive as an event off the bus, or as a URL in config, or not at all. There is
no `RepositoryLookup`-style port here and there should be no need for one: a run knows a repo id, a
branch name and a sha, and everything else it wants it fetches from the git host itself.

**`qits-githost-events` is the first exception, and it is the shape of one rather than a hole in the
rule.** It is a *vocabulary*: four records and the bus, no client, no address, nothing to call. A
published event's shape is exactly the kind of contract a jar may carry, and the alternative —
reading another service's payload by string key — is worse in every direction.

**`qits-containers-client` was the second, and it WAS a client — deleted with the in-process
executor (qits-506), since qits-ci calls no orchestrator now.** The boundary it made the rule say out
loud still holds: what is forbidden is a dependency on another **bounded context** — a
`RepositoryLookup`, an SDK for qits-projects, anything that turns another domain's availability into
this one's. What is allowed is **platform infrastructure**, the platform's single answer to a
capability every module needs rather than one context's model — qits-events for its events, qits-idp
for its identities — and such a jar carries no domain model across, costs a bounded and honest
amount when its far side is down, and brings no framework. A dependency that fails any of those is a
client on another context, whatever it is called.

**Pin at RELEASED calver, never at a snapshot, and 2026-08-12 is why.** Both jars of the time
(`qits-eventstream` and `qits-containers-client`) carried
`1.0.0-SNAPSHOT` behind a comment saying it must not ship as one — correct, and inert, because the
bootstrap seed-published both into the registry and they resolved there for months. A salvage
re-seeded the artifacts store without them and **nothing went red**, because every qits-ci build
afterwards reused docker's cached maven layer: ten CACHED layers, including the one that resolves
dependencies. The first source change since invalidated that layer, resolved for real, and the
release run died on both jars at once. The lesson generalises past these two: **a green build that
did not resolve anything is not evidence that it could**, so when a pin changes — or when the
registry is re-seeded — purge the artifact from `~/.m2` and build, which is the only local way to
ask the registry the question the step container will ask.

Never add a JPA relation to another context's entity. `ci_run.repo_id` is a plain `String` column
in ci's **own** physical database; a foreign key cannot span it.

## Untrusted input

Four things reaching this code are attacker-controlled and must stay that way in your head:

- **Any event payload a `checkout:` trigger reads.** It is another service's word about what
  somebody did, so the branch and sha a trigger resolves out of it are as attacker-shaped as the
  intake POST they replaced — a durable event with a claim row behind it establishes *delivery*,
  never content. `CiIdentifiers.require{RepoId,Branch,Sha}` validates all three at the accept,
  *before* they reach a filesystem path or an argv. **A candidate's `projectId` and `repoName` join
  them, and they are checked ONLY WHEN PRESENT** (`CiIdentifiers.requireRepo`): both reach the same
  clone URL as a path segment, so a value that is there is validated to the same standard — and
  absence is the compatibility arm rather than a refusal, because a listing that answers ids alone
  supplies neither. Every payload is read as a TREE (`readTree`) rather than bound to a record, which
  is also why no `@RegisterForReflection` is owed for one. Never widen those, never bypass them,
  never interpolate an identifier into a shell string. What changed when the intake left HTTP is only
  the answer to a refusal: a 400 to a caller became a WARN and a settled event, because there is no
  caller.
- **The step's `image`.** It comes from a file in the repository being tested and still lands in a
  `docker run` argv as a positional argument — on the far side of the wire now — so it is checked
  here before it is sent and to the same standard: `CiIdentifiers.requireImage` rejects blank and
  anything starting with `-`. Deliberately loose otherwise — which registry hosts, tags and digests
  resolve is the registry's business. `RunnerStepRunner` calls it before every `Launch`, so a value
  refused here never reaches a runner. This is hardening rather than a fix: no exploit through it is
  known (the spec travels as JSON fields and the runner hands docker its arguments one element at a
  time, through no shell),
  and "the argument parser will surely never take this for a flag" is not a claim worth
  re-defending.
- **The step script.** It is code from a repository, and **qits-ci never executes it.** No code
  path here runs repo-controlled code as a host process, and none runs it through `docker exec`. A
  script leaves this process as a field of one JSON frame, on a socket the step container's own
  daemon dialled outbound, and executes as that daemon's child inside a sandbox with
  `capDropAll`, `noNewPrivileges` and resource caps. qits-ci's whole container vocabulary is two
  runner-socket frames — `Launch` (the runner's `docker run`) and `Reap` (its `docker rm`, answered
  with the log tail) — and `exec` is not in it and cannot be: it is not on the runner protocol at
  all, not even as a way to deliver the daemon binary. **This service spawns NO program
  whatsoever**: reading a repository's config is an HTTP call to the git host, and starting a
  container is a frame to a runner, so there is no `git` and no docker CLI on the host and nothing
  left that could be handed pipeline content.

  `bash -c <anything from a repository>` appearing anywhere in this repo, in `src/main` or
  `src/test`, host-side or inside a docker argv or a workload spec, is the regression this paragraph
  exists to make unambiguous. The grep is `grep -rn "bash -c\|PRELUDE_FAILED\|docker exec"` over both modules; it
  must find nothing that executes.

  **"A step container never gets a docker socket" was this section's invariant and it is now false.
  What replaced it is narrower and was chosen deliberately, not conceded:** a step container never
  gets one *silently*. A step declares `docker: true` in its `.config/qits/` pipeline file, the
  spec carries `hostDockerSocket` for that step and no other, the runner is what mounts its own host's
  socket (the path is the runner host's fact — `qits.ci.docker-socket-path` is gone), the
  config diff shows the declaration, and the run row records that step like any other. Such a step is
  **root-equivalent on the host** — the socket is the daemon and the daemon is root, so it can mount
  host paths, start privileged containers and leave the sandbox at will; the cap-drop flags stay on
  and fence the step's own process tree, which is not the same thing as bounding what the daemon will
  do on its behalf. Accepted for the POC under the standing posture (the sources are trusted;
  intra-network hardening is parked and will be addressed platform-wide), and it is why publishing is
  an ordinary step rather than a seam: an unprivileged builder later is a different step *image* that
  stops declaring the flag, and nothing here changes.

  **Every step that does not declare it keeps the sandbox exactly**, which is why
  `StepContainerSettingsTest` asserts the mount's **absence** as hard as its presence. (The real-container
  proof was `CiDaemonGateIT`, which went with the in-process executor; the real `docker run` is
  qits-ci-runner-daemon's and tested there.) Anything that would hand a step more privilege
  than that one declared mount — an undeclared socket, a host mount, a shared network with services,
  a relaxed cap — is a security change, not a convenience.
- **Everything arriving over the ci-daemon control socket.** A container turns hostile the moment
  step code runs in it, so its frames are data about a run: recorded, never trusted. The `daemonId`
  in a `Hello` is a claim the host checks against the connection it already authenticated rather than
  an identity it accepts; timestamps are host-stamped rather than daemon-reported, because a clock is
  the cheapest thing to forge; and an admitted connection is authorized for exactly "deliver data about
  this run" and nothing else, ever.

Step output is bounded by a rolling tail while it is read, so a chatty step cannot OOM the JVM.
Keep it that way; do not buffer a step's output whole.

## Schema changes

`ci/src/main/resources/db/ci/migration/`, hand-written, its own lineage on its own datasource —
**PostgreSQL**, provisioned by this repository's own deployment spec and never shared with another
context's database or migration history.

**The ordinary rule is back: keep appending, never edit an applied one.** `V2__run_causation.sql`
and `V3__release_join.sql` are that rule being followed. The second adds the release join's two
tables — `ci_release_announcement` and `ci_scm_release`, see "The release join" — and touches
nothing that was already there. The first adds `ci_run.causation_id`, the platform's generic CausedRow column,
nullable, no backfill (`trigger_event_id` keeps the history) and part of no constraint. **For a
bus-arrived event the value is set EXPLICITLY in `acceptEventRun`, not left to the stamp** — the
row is written on `ci-trigger-worker`, behind the queue hop where the ambient scope has already
died, so the entity listener would record null; measured on the first live event runs of
2026-08-10, empty `causation_id` beside a full `trigger_event_id`. The stamp covers the manual
trigger, which evaluates on the request thread under the REST filter's restored scope. The
causation decisions themselves are enforced by `ArchRulesTest` in the `ci` module: every `@Entity`
here implements `CausedRow` (CiRun) or declares `@Uncaused` with its reason in the javadoc (CiStep
— its run carries the cause, and its row is written on the run worker where no scope stands;
CiReleaseAnnouncement — `trigger_event_id`
is already the cause, and it is on the row because the published event is stamped with it;
CiScmRelease — `event_id` is already the announcing release; CiOwedEvent — `event_id` IS the event
the row is owed for). A new entity that skips the decision fails the build naming the class.

`V4__run_started_at.sql` and `V5__run_repository_identity.sql` continue the same rule. The second is
the repository-identity campaign's half of it: `ci_run.project_id` and `ci_run.repo_name`, both
nullable, no backfill and part of no constraint. **Nullable is the design, not a shortcut** — only a
qits-projects listing can answer a public name, so a candidate that came off the git host's own
listing carries neither and no historical row has them; a run with no pair builds id-addressed URLs, which is what this service
did before names existed. `repo_id` is untouched and stays the key: the dedupe constraint is built on
it and every existing row is found by it.

`V6`, `V7__run_gating.sql`, `V8__run_release_request.sql` and `V9__run_retry.sql` continue it too,
and the last three are the release-flow set. `ci_run.gating` was the data form of "userflows are
non-gating" — added with a default so every historical row filled as gating, then the default
dropped, which is the V3-era lesson followed. **`V19__run_drop_gating.sql` drops that column**, the
second drop in this lineage after V18 and for a reason of the same shape: the column held a
classification of a verdict rather than a statement about something that happened, and the
classification is ruled out (ticket 9441bc6e). V7 is not touched, for V18's measured reason.
`ci_run.release_request_id` is nullable with **no** default and no backfill,
because null is the ordinary value rather than a value to be filled: every event run not triggered by
a `ReleaseRequestChanged` has none, as does every historical push row, so there is nothing for an existing row to
be and no reading of "absent" to get wrong. It carries a **partial** index (`where … is not null`),
since an index over the nulls would be a second copy of the table for no query, and it is part of no
constraint — the dedupe stays `(trigger_event_id, repo_id, config_path)` and the per-branch collapse
a re-fold needs is already `supersedeByCheckoutBranch`'s. `ci_run.retry_of_run_id` (V9) is the same
shape and the same reasoning, plus one it states out loud: it is deliberately **not** part of the
dedupe constraint, because a null in a unique tuple makes rows never collide and adding it there
would have switched the dedupe off for every run on the platform. A retry gets past the constraint
with a synthetic `trigger_event_id` instead — see "The trigger engine".

`V10__release_announcement_project.sql` is the first migration in this lineage that touches the
release join's tables rather than `ci_run`, and it is the same shape a third time:
`ci_release_announcement.project_id`, nullable, no default, no backfill, part of no constraint and no
index. It exists because `SoftwareRelease` gained `projectId` and the announcement is **not always
made by the run that owes it** — see "There are two publishing seams". Nothing looks a row up by
project; the join key is `(repo_id, version)` and is untouched.

`V11__release_announcement_repo_name.sql` is that same shape a fourth time and its twin:
`ci_release_announcement.repo_name`, nullable, no default, no backfill, part of no constraint and no
index. `SoftwareRelease` gained `repoName` because a project id alone is half an address — the
deployer's name-addressed content read (`/git/<projectId>/<repoName>/blob/…`) is the only one
qits-githost's storage-client guard lets it make — and the column exists because the announcement
outlives the run row that knows the name, exactly as V10's does. The two are written and read
together and should be kept that way; nothing looks a row up by either.

`V13__owed_trigger_event.sql` is the trigger engine's owed-event ledger, and it is the first table
in this lineage that is meant to be **empty**. One row per event the engine has accepted and not yet
evaluated, written before the acceptance is reported to the bus and deleted when the evaluation
returns — the eventstream outbox's shape, for a gap of the same kind: the durable claim is on the
*eventstream* datasource and a run row is here, so the only thing that can be made atomic with the
run is the acceptance. Read its header for the cutover that bought it (2026-09-04, three release
requests with no QA run) and "The trigger engine" for the sweeps that drain it. Keyed by the event
id, so a redelivery finds its own row; one index, on `accepted_at`, which is the sweeps' only read.

`V14__scm_release_priority.sql` is the release-priority campaign's whole schema cost, and it is V8's
shape a fifth time: `ci_scm_release.priority`, `varchar(32)`, nullable, no default, no backfill, part
of no constraint and no index. A release request's priority is declared in qits-projects on its
participating branches, folded there into one effective value, and carried down on `SCMRelease`;
qits-ci **transcribes** it onto `SoftwareRelease` verbatim, and nothing on the announce path compares
it to anything. That file's header says "`ci_run` gains no column, the FIFO queue is untouched" and
ends "queue ordering is the next feature and this is the inert data it will read" — **V15 is that
feature**, so read the two columns as the different facts they are rather than as a duplication: this
one is what the RELEASE said, resolved at announce time from the fact row, and V15's is what a RUN
was accepted knowing. It lands on the release fact rather than on the owed announcement for V10's
reason exactly reversed in time: the announcement is often made by whoever closes the join later, and
the fact row is the half that knows what the release said, so `ReleaseJoin.announceOwed` resolves it
there at announce time. No check constraint names the six values — the vocabulary is another
context's and will grow there — and the only rule applied is the column's own width, a longer value
recorded as **none** with a WARN, `ci_run.release_request_id`'s rule verbatim.

`V15__run_ordering_inputs.sql` is the queue-ordering campaign's whole schema cost, and it is V8's
shape a **sixth** time twice over: `ci_run.priority varchar(32)` and `ci_run.downstream_repos text`,
both nullable, no default, no backfill, part of no constraint and carrying no index. They are the two
inputs `CiRunOrdering` ranks the queued rows by — see "The queue is the table, and workers claim out
of it" — read at accept off the triggering event's own payload and by nothing else, ever.

Four decisions in it are worth having in front of you. **The closure is stored verbatim as the
canonical JSON array text**, `trigger_event_payload`'s precedent and its reason: this module walks
payloads rather than binding them, so the honest column for a list of another context's words is the
text it arrived as, and the ordering parses it once per pass rather than once per comparison. `text`
rather than `varchar` because the list's length is the platform's dependency graph, not a number this
schema should pick. **Nullable is the ordinary value rather than a gap** — most events carry neither
field, and NON_NULL makes "stated none" and "predates the field" the same absent key, so both are
read as UNKNOWN: unconstrained for the topology, `MEDIUM` for the priority, never an error and never
a refusal. **The width equals `ci_scm_release.priority`'s deliberately**: a value one row could hold
and the other could not would make one release read two ways depending on which row was asked. And
**no index**, because the only reader is a reservation's scan of the `QUEUED` rows, which is bounded
by the accepted backlog; an index over two columns that are null on most rows would be a second copy
of the table for nobody. A platform where nothing states either orders exactly as it did before the
migration, by `(created_at, id)`.

`V16__run_expected_step_durations.sql` is the expected-duration feature's whole schema cost, and it
is V8's shape a **seventh** time: `ci_run.expected_step_durations text`, nullable, no default, no
backfill, part of no constraint and carrying no index. It holds a JSON array of millisecond longs,
one entry per planned pipeline step — `downstream_repos`' storage decision for its reason, since the
array's length is the pipeline's and the value is read whole and queried into by nothing.

**It is a PREDICTION and `ci_step.started_at`/`finished_at` are the measurement**, which is the one
confusion worth guarding against: this column is the p95 of those two, over the most recent 25
`SUCCESS` rows of the same step index of the same `(repo_id, config_path)` with the same image,
computed once at accept by `CiRunService.predictedStepDurations` and never revised. A run that
overruns it is a slow run, not a row to correct. **Nullable is the ordinary value**: a prediction
exists only when *every* planned step has history, so a repository's first run, the first run after
a pipeline grew a step, the first run after a step changed its image and every historical row all
carry null — and a partial prediction is deliberately not a thing, because the missing segment would
be a guess wearing a measurement's clothes. The image is part of the sample predicate, and that is
the whole of how a changed pipeline stops predicting: no invalidation and no version column, just
samples that stop matching. The prediction is computed **outside** the insert's transaction on
purpose — it is a read, a read that throws inside the insert bracket would poison the session and
roll the accepted run back, and the standing rule is that a convenience never costs a build. A
malformed stored value reads back as **no prediction** (`ExpectedStepDurations.decode`) rather than
throwing, so a column nobody can fix costs one field and not every listing. A **retry** re-predicts
rather than copying, unlike the `priority`/`downstream_repos` beside it: those say what the work is
worth and a re-fire is worth what it re-fires, while this says how long it takes and the honest
answer is the one the history gives now.

`V1__init.sql` is the rest of the schema. The nine H2 migrations it replaces (V1-V8 plus a Java V9) are
history in this repository's log and are not a prefix of this lineage: the move off H2 is a
re-bootstrap rather than a data migration, so no postgres database anywhere ever ran them and no
`V10__move_to_postgres.sql` had a reader. Read that file's header before adding anything — it argues
each of the schema's remaining decisions, and the check constraints in particular.

**What the H2 lineage taught, kept here because the lessons outlive the files.**

- **Backfilling a `not null` column into a live table** (V3): add it *with a default*, so the
  `alter` writes every existing row correctly, then `drop default`, so a future insert that forgets
  the value fails loudly rather than getting a silent one. The fresh V1 needs neither step, since
  every database reaching it is empty — but the next such column will.
- **Widening a check constraint the original script never named** (V4). V1 declared its status
  domains inline — `status varchar(32) not null check (status in (...))` — so H2 generated the
  names, and there is no portable way to drop an anonymous constraint. V4 could name `CONSTRAINT_76`
  only because it had *measured* that one database. Its replacement was named, and it wrote one
  `QUEUED` row and deleted it again as a probe: a database whose V1 check had landed under a
  different generated name would have taken the drop as a no-op and then rejected every accepted run
  at insert — silently in every JVM test, loudly only in the deployment. **If you ever declare a
  constraint, name it.**
- **The defect that ended the whole story.** H2 2.4.240 keeps a checked IN-set tied to the session
  that compiled it: once the pool retires that session, a valid write fails with `23514 Check
  constraint invalid`. V5 dropped the three checks it could name — but two of those names never
  existed, so `ci_step` kept its generated one, and on a freshly bootstrapped platform every `insert
  into ci_step` failed 23514 after a few long builds and runs died step-less. `V9` had to be a
  **Java** migration reading `INFORMATION_SCHEMA.TABLE_CONSTRAINTS`, because the generated names
  depend on the order the DDL was replayed and no script can name what it cannot know.

**Postgres has none of that defect, and the checks did not come back anyway.** `ci_run.status`,
`ci_run.trigger_type` and `ci_step.status` are catalogues that have grown once already — V4 added
`QUEUED`, `EVENT` joined `POST_RECEIVE` — so the invariant lives where the writes are:
`CiRunStatus`, `CiTriggerType` and `CiStepStatus` are `@Enumerated(EnumType.STRING)` and no code
path writes a status any other way. **A new status value is one enum constant and no migration.**
**A RETIRED one is not even that**: `POST_RECEIVE` and `CONFIG_ERROR` have no writer since
2026-09-05 and both constants stay, because the column holds the STRING and rows carry it — deleting
either would make history unreadable. There is no migration and no backfill for a retirement.
**There is no check constraint left in this schema at all.** `ck_ci_daemon_pin_verdict` was the one
that stayed — a verdict being a closed statement about one probe's outcome rather than a growing
catalogue, and named, so widening it would have cost one line — and `V18` drops it with the table it
guarded. Being named is exactly what made it free to remove. `CiSchemaTest` runs the real migration
against a real postgres and pins all of that, including that `ci_daemon_pin` is really gone and that
the unbounded columns came out `text` and not a large object.

`V21__run_step_images.sql` is V8's shape a **ninth** time: `ci_run.step_images text`, nullable, no
default, no backfill, part of no constraint and carrying no index. It holds one JSON object per run
— each distinct image reference its steps named, mapped to the immutable digest reference it was
pinned to at accept — and it is `V20`'s twin rather than another column beside it: V20 records which
recipe composed the pipeline a run executed, this records which image that pipeline executed
inside, and a run row then says what it was built from on both axes. `text` and read whole is
`downstream_repos`' decision for its reason; nothing queries into the value. **Null means three
things and none of them is a value that could be filled in**: a pipeline whose every step names an
image this platform does not publish pins nothing, a deployment with
`qits.ci.resolve-platform-step-images=false` pins nothing by design, and every row older than the
column genuinely does not know — a digest written for a run that already happened would be a claim
about bytes nobody can now check. What is *not* a null is a platform image the registry would not
answer about: that refuses the accept outright, so there is no row. See "A run is fixed to one
toolchain".

`V22__run_archetype_version.sql` is V8's shape a **tenth** time: `ci_run.archetype_version
varchar(64)`, nullable, no default, no backfill, part of no constraint and carrying no index. It is
V20's other half: that migration records WHICH WRAPPER COMMIT composed a run, this records which
released wrapper VERSION that commit is — the name a release request carried and a person approved,
which no git host will answer back from a sha. It landed with the change that moved the archetype
read off the wrapper's `main` head and onto the wrapper's newest released version; null means what
V20's three nulls mean, plus every row composed while the recipe still came from `main`, and there
is nothing those rows could be filled in with. **The two columns changed meaning with qits-583 and
neither migration was edited** (their headers still name the wrapper; a checksum covers prose):
`archetype_rev` is now non-null only for a recipe the repository itself carries, read at the run's
own commit, and `archetype_version` is non-null only for a packaged recipe and is this
qits-ci-service's own version. `CiRun`'s javadoc is where that is stated. 64 characters, `archetype_rev`'s width, so a value
one of the pair could hold and the other could not can never exist.

`V23__runners.sql` is the first **table** since V13 and the runners epic's (qits-440) whole schema
cost so far: `ci_runner` — one row per runner an operator declared, its registration state being
which of two nullable pairs is set (`registration_token_id`/`_subject` at create and rotation,
`client_id`/`registered_at` once, by the register door) — and `ci_run.runner_id`, V8's shape again
with V8's partial index, since "may this runner be deleted" and "how many runs does it hold" both
look runs up by it. **No foreign key** from the run to the runner, for `repo_id`'s reason: a
decommissioned runner leaves its runs as history. `plane` is an enum column with no check, like
`status`; `capabilities` is the lineage's first `jsonb`, because the next epic's scheduler queries
into it. `CiSchemaTest` pins the name constraint, the absent key and the two defaults.

`V24__runner_quarantine.sql` is the quarantine's (qits-466): eight nullable-or-defaulted `ci_runner`
columns — `quarantined_at`/`quarantine_reason` set and cleared together, `infra_failures` (`not null
default 0`, and the default stays: 0 is what a new row really has), `infra_failure_runs` (the streak's
distinct run ids as JSON array text, `downstream_repos`' decision), and the newest health check's four
`last_healthcheck_*` — plus `ci_run.purpose` (`not null default 'BUILD'`, which KEEPS its default so
every insert path that predates health checks writes a build without learning a word) and
`ci_run.target_runner_id` (V8's partial-index shape, no foreign key). **A health check is a run and so
lives in `ci_run`, and that costs every repository-scoped read a predicate**: `CiRunRepository`'s
listings, newest-run reads, finished listing and `distinctRepoIds` carry `purpose = BUILD`, while the
queue's reads (`listActiveNewestFirst`, `listQueuedOldestFirst`, `countQueued`) keep every run — the
commission reconciler must not reap a pending check's credential — and their callers filter
(`CiRunService.builds`). A new repository-scoped read owes the predicate too.

`V29__release_announcement_if_published.sql` is V10's shape twice (qits-561):
`ci_release_announcement.announce` (the entry's policy, `if-published` or null for `always`) and
`ci_release_announcement.skip_reason` (`ABSENT`/`UNVERIFIED` for a row settled without an
announcement, null otherwise), both nullable, no default, no backfill, no constraint, no index. The
policy is on the owed row for `finished_at`'s reason — the drive that closes the join is often not
the run that owed it — and a skipped row carries `announced_at` like an announced one, so every
existing reader of "owed" (`announced_at is null`) is right without learning the new column.
**qits-648 retired the `announce` half**: nothing writes it since `announce:` was deleted, and it
stays in the schema rather than being edited out of V29. A row owed before the deletion with
`if-published` is decided as an `if-changed` one (`ReleaseJoin.isIfChanged`), and every row written
before reads through the artifacts door as it did (`publish` null reads `always`).

`V30__release_announcement_decision.sql` is that shape three more times (qits-640, release A):
`publish` (`if-changed`, null for `always`), `decision` (`PUBLISHED`/`UNCHANGED`/`ABSENT`/
`UNVERIFIED`, null while owed) and `unchanged_since`. The join writes `decision` beside
`skip_reason` and changes nothing about which rows it announces: an announced row is `PUBLISHED`,
a skipped one names its skip reason. A row settled before V30 has `announced_at` and no `decision`,
and `ReleaseJoin.decisionOf` reads it from `skip_reason`. The read is
`GET /ci/api/repositories/{repoId}/releases/{version}/artifacts` — newest run first, one entry per
`(type, name)`, an empty list rather than a 404.

Release B of qits-640 gave `UNCHANGED` its writer. A `publish: if-changed` row (a declared maven or
npm entry, or a contract package `CiReleaseComposer` expanded from `contracts:`) is probed at the
release version; ABSENT there is followed by `CiArtifactPresence.newest`
(`GET /artifacts/content-hashes/<type>/<name>/-/newest`), and a newest version `v` settles the row
`UNCHANGED`, `unchanged_since = v`, `skip_reason = 'UNCHANGED'`, not announced; a 404 there is
`ABSENT`. The same release composes the publishing postlude: one `qits artifacts publish
maven|npm` per entry in `link:` order, `contract`, `contract-docs` and `docs submit --openapi`, all
on the release slot's last step (`CiReleaseComposer.publishStep`). The pinned
`qits.platform-access-cli-binary.version` must name a CLI that has those commands.

`V31__release_announcement_section.sql` is V10's shape once more (qits-666):
`ci_release_announcement.section`, nullable, no default, no backfill, no constraint, no index.
`SoftwareRelease` gained `section` (`artifacts` | `contracts`) and `runId`, both appended after
`priority` with delegating constructors and NON_NULL absence. The composer marks each contract package
it expands `section: 'contracts'` in the composed `artifacts:` block (nothing on any other entry, so no
other golden moved), `CiEventTriggerParser` reads it back into `CiArtifact.section`, `ReleaseJoin.owe`
copies it onto the owed row, and the announcement reads it off the row; `runId` needs no column, the
row has carried `run_id` since V3. A row owed before V31 announces with no section.

`V32__runner_connection_loss_window.sql` is V24's shape (qits-748): `ci_runner.connection_loss_window_start`,
nullable, no default, no backfill, no constraint, no index. It is when the runner's last *counted*
`CONNECTION_LOST` was recorded; `CiRunners.recordInfraFailure` counts a further connection loss only
outside `qits.ci.runner.quarantine.loss-window` of it, so one edge redeploy ending every held run at
once is one failure rather than one per run. Cleared with the streak (`recordStarted`, `reinstate`).

`V27__run_avoid_runners.sql` added `ci_run.avoid_runner_ids text` (nullable, no default, no
constraint, no index) for "a retry is not handed back to the runner that failed it" (qits-556). That
behaviour was removed (qits-443, the owner's ruling of 2026-09-30: never specified; with a single
runner it strands the retry forever). **The column is debt**: it stays in the schema because an
applied migration is never edited, the entity does not map it, and nothing reads or writes it — rows
that still carry a set are reserved like any other. Dropping it is a later migration of its own.

`V18__retire_daemon_pin_ladder.sql` is the **first migration in this lineage that drops anything**,
and it owes an argument the additive ones do not. Every file since V1 has added a nullable column and
kept what was there; this one removes `ci_daemon_pin` outright, because the rows were not history.
This lineage keeps history wherever a row is a *statement about something that happened* — which is
why `CiRunStatus` keeps `POST_RECEIVE` and `CONFIG_ERROR` as constants — and a `ci_daemon_pin` row
was the **current state of a decision procedure**, read on every launch and rewritten by every probe.
A decision procedure that no longer exists has no current state, and kept rows would be verdicts
about candidate versions that nothing can produce, nothing will read, and nothing can explain.
What *is* history is untouched: `ci_run.daemon_version` still records what each run really launched,
and the `SoftwareRelease` frames the adoptions were derived from are still on the event log. The
file's header carries all of this, **and `V1` is not touched at all** — which is the half that cost a
release to learn twice. V18's first cut added five comment lines to `V1`'s ladder block saying the
table ended here; 2026.917.45603 would not boot, the deployment rolled back to 2026.917.40824 and the
schema stayed at 17, so the drop never ran. Flyway checksums a migration over its whole file, prose
included, and validates every applied one at boot: an annotation on `V1` is a different `V1` to every
database that has already run it. The same edit, the same rollback, had happened on 2026-08-23
(2026.823.164332, reverted by `7bc1dc6`). **Say it in the newest migration.** A reader who finds the
ladder described in `V1` finds its retirement two entries later, which is how a lineage is read
anyway.

**`MigrationChecksumTest` is what makes that rule mechanical rather than remembered.** It pins the
SHA-256 of every file in this directory, so editing an applied one is a red build here instead of a
rollback in the deployment, and adding a migration is adding its pin in the same commit. It hashes
the bytes rather than reproducing Flyway's own CRC32: that one lives in an internal class and
normalises line endings, so it is both a moving target across upgrades and weaker than byte identity.
A pin therefore fails on edits Flyway would have tolerated, which is the right direction — a version
that genuinely has not shipped is a one-line pin update beside the content change.

The locations list is shipped **once**, in the jar's `META-INF/microprofile-config.properties`, with
no copy in either test resources file — and it names one directory now that there is no Java
migration to point at. `baseline-on-migrate` is gone with the H2 file it existed for.

## Authentication

**`qits:admin-agent` is admitted wherever `qits:admin` is** (qits-628 follow-up): an ADMIN
workspace's coding agent carries it alongside `qits:agent`, and for now it may use everything
`qits:admin` may use. Stated explicitly beside every `qits:admin` check, never implied, so a later
per-endpoint removal stays possible.

**Two identity tracks, and nothing in this repo implements either.** Both arrive in the
`qits-auth-core` jar (`components/qits-integrations/qits-integrations-quarkus-javalib/`), and
`service/pom.xml` says so in a comment beside the dependency. There is no `security` package here any more, and no filter.

**Users** are headers. `ForwardAuthMechanism` reads `X-Qits-User` and `X-Qits-Roles` into a
`SecurityIdentity`; this service authenticates no person. The platform edge establishes the
session, and `qits-gateway` strips and re-asserts its reserved `X-Qits-*` namespace. That hygiene is
the entire reason the headers can be trusted here. There is no auth variant to select in this
service: the read routes accept `qits:admin` or `qits:system`, the two human writes (`cancel` and
`retry`) require `qits:admin`, the release-request cancellation **and the manual trigger** take
either role and add a `MachineAuth` check on their machine arm — the trigger's reads the `project`
claim as a scope rather than demanding one value — and machine controllers retain their separate
`MachineAuth` audience/scope checks pending endpoint-scoped machine roles.

**`identity.isAnonymous()` is not a security state** — it means "no name for the audit row". A check
of the form `if (identity.isAnonymous()) deny` would look like a security control and be worth
nothing. It is also not the machine question: a machine caller is a `JsonWebToken` principal, which
is what `MachineIdentity.isMachine` reads and what makes an anonymous *user* and an absent *token*
two different facts.

**Machines** are a bearer, and the guard is `MachineAuth`. qits-idp mints the token; quarkus-oidc
validates its signature, issuer and expiry; `MachineAuth` then asks the two questions this service
owns — is it addressed here (`aud` contains `qits.auth.machine.platform-audience`, which the
library itself ships as `qits-platform`, the one audience qits-idp puts on every token it mints; it
is a fact about the platform and not about a deployment, so nothing here sets it), and does its
`project` claim cover the target. A missing claim is a mismatch, never a wildcard; `project=*` on the **token** covers everything, but `"*"` as the *target* is compared like
any other string, so a caller cannot widen its own check. Failures are 401 with no machine token and
403 with the wrong one, both mapped by quarkus-security rather than by `CiExceptionMapper`.

**It sits beside forward auth rather than instead of it.** A request with no `Authorization` header
is not challenged — the tenant is bearer-only — so it falls through to the header mechanism and stays
user traffic.

**One platform-wide gate, `qits.auth.machine.required`, shipped `false`, and there is no third
state.** Off, every `require*` call returns at once, `quarkus.oidc.tenant-enabled` is off with it, no
JWKS is fetched and a clone-alone `./mvnw verify` needs no issuer anywhere. That is what let this
service ship enforcement before qits-idp was deployed. On, the same call demands a validated token —
and a deployment that turns it on with no audience configured fails at **startup** rather than
accepting tokens meant for another service. Which endpoints call the guard, and what a new one owes,
is under "Addressing"; the deployment steps are in `README.md`.

**This service presents a credential to two hops, and both are the same identity — the one named
oidc client `qits`** (epic qits-540 dossier, 'Plan (as of 2026-09-13)', C4; it replaced a default
client audience-bound to qits-containers and a separately named `githost` one audience-bound to
qits-githost). It asks qits-idp for a token to present to **qits-githost**, addressed with the
single audience `qits-platform`, and it presents the same client id and secret as HTTP Basic to
**qits-idp itself** to commission one credential per run (see "The credential is commissioned per
run"). One is a bearer this service holds, the other a credential it mints for a container; both
live or die with `quarkus.oidc-client.qits.client-enabled`. There was a third hop, the bearer
presented to qits-containers (whose `OwnerGuard` made the client id this service's owner string,
`qits.ci.containers.owner`); it went with the in-process executor in qits-506, along with
`containers/ContainersClientProducer`.

**Its id, secret and idp address come from the deployer and nowhere else.**
`.config/qits/deployments.yml` declares `resources: idp:client`, so qits-deployments provisions this
application's own qits-idp service client and injects `QITS_RESOURCE_IDP_URL`,
`QITS_RESOURCE_IDP_CLIENT_ID` and `QITS_RESOURCE_IDP_CLIENT_SECRET`; `application.properties` reads
those three and nothing else (the shipped defaults are the dev estate's `dev-qits-idp` alias,
derived from `QITS_ENVIRONMENT`, `dev-qits-ci`, and an empty secret). No configuration entry names
any of them, and the old `QUARKUS_OIDC_CLIENT_*` fallback is gone.

One switch, `quarkus.oidc-client.qits.client-enabled`: shipped **true**, because every deployment
has the resource, and **false** under `%dev` and `%test`, so a suite or a local run builds a
disabled client, boots with no secret and dials nothing. **It is also the commissioning switch**:
`IdpCommissioner.enabled()` reads the same key plus both halves of the credential behind it, so a
process with the client off commissions nothing and launches no step.

**Two other client NAMES still ship keys, and they are neutralisation rather than dead stubs.** The
container still carries `QUARKUS_OIDC_CLIENT_*` (the old unnamed default client, five keys) and the
whole `QUARKUS_OIDC_CLIENT_GITHOST_*` family — nothing reads them and
`.config/qits/configuration.yml` no longer declares them, but **one such variable mints the map
key**: both clients exist at runtime whatever `application.properties` leaves out, with
`client-enabled` and `discovery-enabled` defaulting to true. An enabled client is resolved during
**runtime init** — `initOidcClients` awaits `createOidcClient` per client before the HTTP listener
accepts — so an issuer that accepts and does not answer fails the boot on a mutiny
`TimeoutException`, which is how deleting those two blocks as "dead" on 2026-09-15 shipped a boot
hazard in `2026.915.174621`. Three keys per name close it and each does a different job: with no
variable set `client-enabled=false` disables the client outright, and where the deployment DOES set
the variable it wins (env is ordinal 300, this file 250) — so `discovery-enabled=false` is what
removes the dial, and `token-path` is what stops that same discovery-less client failing runtime
init on a token endpoint it may no longer discover. The `qits` client is untouched by all of it: it
reads only `QITS_RESOURCE_IDP_*`, environment VARIABLES named inside `${…}`.
`OidcClientNeutralisationTest` measures both arms against a real `EnvConfigSource`, which is the
only way to see it: a surefire JVM gains no environment variable and a `QuarkusTestProfile` override
is read by the expressions but never by a dotted key. The keys go once no such variable reaches the
container — the config GC deletes the retired entries and the deployer's extras file stops stating
them (qits-375) — not before.

**This is a NEW arrangement rather than the old one coming back.** The retired one was
`notify/PdBearer`, a bearer for qits-platform-deployments' HTTP intake, and it went with the call it
carried when the deployer started subscribing off the bus instead. What the pom said in the gap —
"adding the extension back means a new outbound caller, not a config change" — is exactly what
happened: a new caller arrived, so the extension did.

**The fetch is bounded.** It sits on a run's thread (a config read, a commission), so the producer writes
`getTokens(oidcClient).await().atMost(…)` and never any `…AndAwait` spelling; `TokensHelper` caches
and refreshes, so a restarted idp pauses new issuance and nothing else, and a fetch that fails costs
the **header** rather than the call — the `TokenSource` contract is that a source which throws is a
source that returned nothing, so a broken idp is a 401 from qits-githost rather than an exception
on a build slot.

**`MachineGuardTest` blanks `qits.auth.forward.dev-user` in its profile, and that is not tidiness.**
Under `%test` the forward-auth mechanism answers every request carrying no `X-Qits-User` with a
synthetic `dev` identity, so it authenticates first and the bearer is never consulted. Left set,
every case in that file sees a user rather than a machine and the 401 cases pass for the wrong
reason. A real machine call has no such header and no such fallback.

`ForwardAuthTest` lives in qits-auth-core with the mechanism it covers, and its argument is worth
knowing when you read it: it sets a real `X-Qits-User` rather than reaching for `@TestSecurity`,
because the header **is** the contract. `MachineGuardTest` goes the other way and uses
`@TestSecurity` with `@OidcSecurity` claims on purpose — what is under test here is this service's
decision about a token's claims, while whether a signature or an expiry is checked is quarkus-oidc's
contract, tested where it lives.

## Tests

- App-level config lives in `service/src/main/resources/application.properties` — this module is the
  deployable, and Quarkus merges that file into the test config rather than letting
  `src/test/resources/application.properties` shadow it. **Never re-declare an app-level setting in
  test resources**: a suite green because the *test* copy is right proves nothing about what ships,
  and the two silently drift. `src/test/resources/application.properties` carries only genuine
  test-only overrides (`clean-at-start`, `target/` working dirs, a git-host url nothing answers on).
- **The databases in the suites are a real PostgreSQL, spawned as a child process.** Zonky resolves
  postgres binaries as ordinary Maven artifacts, so the clone-alone, docker-free rule survives the
  move off H2: never Testcontainers, never a dev service (`quarkus.devservices.enabled=false` says
  so out loud in both modules). `testdb/EmbeddedPg` starts ONE instance per surefire JVM and tracks
  its port in a **system property**, because a Quarkus run loads config sources in more than one
  classloader and a static field is not shared across them; `testdb/EmbeddedPgConfigSource` hands the
  url/username/password to every `@QuarkusTest` at an ordinal above `application.properties`, since
  the port is chosen at run time and cannot be written into a file. Every (module, datasource) pair
  names its own database — `ci_domain`, `ci_svc`, `eventstream_svc`, and the IT's `ci_packaged_it` /
  `eventstream_packaged_it` — so two suites on one host can never mean the same one. The suites set
  the datasource VALUES rather than the `QITS_RESOURCE_*` variables, deliberately: a suite that had
  to export the variables could not also say what happens when they are missing.
  `CiPackagedSurfaceIT` is where the shipped expressions themselves are exercised, and it passes the
  variables through **system properties**, not a static field, for the two-classloader reason above.
- **The git host in the suite is `githost/StubGitHost`, and it is a server rather than a
  directory.** Reading pipeline config is HTTP now (below), so the old stand-in — a `file://`
  directory laid out as `<base>/git/<repoId>` — answers nothing, and the shipped test config points
  `qits.ci.git-host-url` at `http://127.0.0.1:1`: an address nothing listens on, so a suite
  that never seeds a repository fails its reads fast and honestly. A suite that *does* declares
  `@WithTestResource(value = StubGitHost.class, scope = TestResourceScope.GLOBAL)`, which is how the
  port reaches the application's config **before it boots** — the same arrangement as
  `StubEventsServer`, and `GLOBAL` so it costs no restart. The stub still serves ordinary bares the
  suites build with real `git`; it shells `git` to answer the two content routes, so what is faked is
  the wire shape and nothing else. The ITs call `StubGitHost.start(root)` from their profile instead,
  because they own their own root directory.
- `OpenApiSchemaExportTest` writes `docs/openapi.yml`. Regenerate and commit when the surface
  changes: `./mvnw -pl service -am test -Dtest=OpenApiSchemaExportTest
  -Dsurefire.failIfNoSpecifiedTests=false`. Both extra flags are load-bearing: `-am` because the
  reactor's own modules are not installed anywhere, so `-pl service` alone cannot resolve them, and
  `failIfNoSpecifiedTests=false` because `-am` then walks the sibling modules, which have no test by
  that name.
  **The document holds the read surface and the one write, and exactly one operation is hidden.**
  The criterion has always been "does a first-party client consume it, does a person invoke it" —
  machine surfaces stay out. For a long time that left `paths: {}`, then one path (`POST
  /ci/api/runs/{runId}/cancel`, the one operation here a person invoked on purpose — it has since
  been joined by `POST /ci/api/runs/{runId}/retry` and `POST /ci/api/runs/cancellations`), because no
  client read anything. **qits-spa-ci changed the answer, not the criterion**: it reads `GET
  /ci/api/repositories`, `GET /ci/api/repositories/summary`, `GET /ci/api/runs`, `GET
  /ci/api/runs/active`, `GET /ci/api/runs/finished` and `GET /ci/api/runs/{runId}` on every page it
  draws, so those are the JSON
  API a first-party client consumes and none of them carries `@Operation(hidden = true)`.
  Keeping them hidden would have meant this file omitted the entire contract that client depends on,
  and a breaking change to `CiRunDto` would have landed with an **empty diff** — which is the exact
  opposite of why the file is committed. **Nothing is hidden any more.** The one operation that was,
  `POST /ci/api/events/post-receive`, is gone with the HTTP fan-out it served — it was machine-only
  and its wire contract lived in the git host's repository, which is exactly what the criterion keeps
  out. `POST /ci/api/events/trigger` is the same criterion answering the other way and was always in:
  a person invokes it on purpose and its contract is written down nowhere but here. The guard the two
  shared had nothing to do with either decision.
  **`GET /ci/api/daemon` is in for the mirror-image reason** and is worth having as the worked case
  of a *machine* consumer that still belongs in the document: it is unguarded, its contract lives
  here rather than in the service that reads it, and qits-artifacts' daemon GC reads it fail-closed —
  so a change to its shape stops a sweep in another repository, which is precisely the class of
  change that must not land with an empty diff. "Machine surfaces stay out" was never about the
  caller being a machine; it is about where the contract is written down.
  The file is committed precisely so that hiding or unhiding an operation shows up as a diff.

  **`?limit=` on the run listing binds as a `String` on purpose.** JAX-RS answers a *query*-parameter
  conversion failure with a **404** (the spec says so for `@QueryParam`, `@PathParam` and
  `@MatrixParam` alike), so an `Integer limit` would answer `?limit=abc` with "no such resource"
  instead of "bad request". The parameter is parsed in the resource and rejected through
  `CiExceptionMapper`'s `{"message": …}` envelope like every other bad input here; the OpenAPI
  document still declares it `integer, minimum 1` via `@Parameter`, because the document describes
  the contract rather than the binding.
  Note the test runs as a `@QuarkusTest` and indexes the test classpath, so a `@Path` resource under
  `src/test` would land in the document — that is why `IdentityEchoResource` is hidden too.
- A `Failed to start quarkus` / `Port already bound: 8081` failure is the known flake
  (`migration-plan.md` §9 item 14) — `@QuarkusTest` restarts racing for the test port. Re-run first.
  `CiPackagedSurfaceIT` is deliberately outside that race: failsafe passes it
  `quarkus.http.test-port=0`, so the packaged app it launches takes a free port instead of queueing
  behind whatever surefire has not finished releasing. `eventstream/`'s suite sets the same key in
  its own `src/test/resources/application.properties`, for a version of the same reason it can
  actually fix: that module registers no route at all — quarkus-websockets-next is there for its
  *client* — so the server a `@QuarkusTest` starts is incidental, and three test classes asking for
  three configurations means three restarts racing one port.
- **The eventstream suite is the submodule's, and its conventions are documented there.** It runs in
  this reactor and its failures land in this build, which is the only reason it is mentioned here:
  when it goes red, read `eventstream/AGENTS.md` under "The suite" rather than debugging it from
  this side, and fix it in that repository. The one fact worth carrying across is that its
  `StubEventsServer` is where this module's trimmed copy came from — see the bus test bullet below.
- `CiPackagedSurfaceIT` is the only test that runs the **packaged artifact** — the fast-jar under
  `-DskipITs=false`, the binary under `-Dnative`. It is not a second boundary test and behaviour
  does not belong in it: it asserts the handful of things a `@QuarkusTest` structurally cannot see,
  because they only exist once the app is built (the routes' build-time prefixes, the shipped
  datasource EXPRESSIONS — both of them, handed the `QITS_RESOURCE_*` variables rather than the
  datasource keys — Flyway's migrations surviving as resources, SnakeYAML and Panache on a real run,
  that `/ci/daemon` is on the artifact's router, and — the same argument, applied to Quinoa — **how
  the client and the machine surface divide `/ci`**). Its pipeline declares no steps, so it needs no
  container; step execution against real containers is the runner's, tested in qits-ci-runner-daemon
  (`CiDaemonGateIT`, which drove it here, went with the in-process executor).
  The SPA probes are qits-events' list (`docs/project-setup-quinoa-angular.md`), and closing that
  asymmetry was overdue: `/ci/` serves 200 HTML carrying the client's own `<base href="/ci/">`, a
  deep link (`/ci/runs/anything`) falls back to `index.html` so the Angular router owns it across a
  reload, `/ci` redirects 301, and `/ci/api/nope` and a plain `GET /ci/daemon` each answer 404 and
  **not the client**. The assertion is "not the client" rather than "never HTML" because what comes
  back is Vert.x' own stock `<h1>Resource not found</h1>`, which is `text/html` and correct — pinning
  the content type alone would fail against the right behaviour and still pass against the wrong one,
  since `index.html` is `text/html` too. All five are invisible to surefire by construction (Quinoa
  is off in test mode), which is exactly why they belong here.
  The `/ci/daemon` assertion is there because websockets-next registers that endpoint at
  *augmentation*: "the extension is native-image supported" is a claim the binary has to prove here,
  and a native build that silently dropped the route would otherwise surface as every run stuck at
  "never registered" with nothing in any log to say why. It dials with credentials no registry can
  know and asserts the **upgrade succeeds and the server then closes 1008** — a missing route fails
  the upgrade with a 404 instead.
- **`api/TokenValidationBootstrapIT` is the second test against the artifact, and it is the only
  place the OIDC tenant is ever ON.** The shipped tenant is gated —
  `quarkus.oidc.tenant-enabled=${qits.auth.machine.required:false}` — and every suite here leaves
  that gate shut, so the block this service deploys with (auth-server-url + `jwks-path=jwks` against
  a real listener, audience enforcement, the `groups` claim becoming roles) is exercised nowhere
  else. `MachineGuardTest` comes closest and stops exactly where this one starts: it flips the same
  gate but installs its identity with `@TestSecurity` + `@OidcSecurity` claims, so nothing there
  fetches a key or validates a signature. The far side is qits-service-mock's `MockIdp`, which
  serves a real JWKS for a generated keypair, mints RS256 bearers against it and **records what it
  answered** — so "the service fetched the keys at startup" is an assertion and not an inference.
  <br>Its `@TestProfile` **extends `CiPackagedSurfaceIT.PackagedUnderTarget`** rather than copying
  it — what a launched qits-ci needs in order to boot is one answer, and the two `QITS_RESOURCE_*`
  triples with their system-property parking are written out over there — and adds only the gate,
  the mock idp's address, and the keys a host-run process needs because it has no deployment
  behind it: otel dark and the bus dark. **No readiness key is among them**: the story asserts
  `/ci/q/health/ready` answers 200 before any story has connected a runner, and it does with nothing
  set, because `ci-runners` is always UP — an auth story has no runner to bring and needs none. (It also used to point
  `qits.containers.url` at a recording stand-in, `MockContainers`, and shrink the boot reap's
  patience; qits-ci asks no orchestrator since qits-506, and the build stories play a runner instead
  — below.)
  **<br>It used to set two more and needs neither.** `qits.ci.daemon-autoadopt-enabled=false`
  darkened a *second* dial to `qits.events.url` that `qits.eventstream.enabled` did not cover — the
  daemon pin ladder's startup discovery — and had to be repeated here because a launched artifact
  runs under neither `%dev` nor `%test`. And `qits.ci.daemon-version` was set to a plausible CalVer
  nothing ever resolved, purely so `/ci/q/health/ready` came up: the daemon readiness check was DOWN
  whenever the ladder had no rung at all, which is exactly an isolated boot with no qits-events to
  adopt from, so an auth story had to pin a version to be allowed to talk about auth. Both went with
  the ladder — there is no discovery, and the version comes from the pinned protocol dependency and
  can never be absent.
  <br>**That profile is now shared by every story class in this repository, and that is the rule
  rather than an accident.** A `@TestProfile` is what decides whether failsafe launches another
  process, so one profile is one launched qits-ci for the whole failsafe phase; every seam any story
  class needs — the mock idp, the gate — lives in this one file. Adding a
  story class with a profile of its own would double the phase and give the second process a
  different port, a different database and an empty run history.
  <br>The guarded route both stories present a bearer to is `GET /ci/api/runs/active`: a plain read
  of ci's own rows that dials no other service, class-level `{qits:admin, qits:system}` so the
  machine role a platform peer holds is enough, and parameterless — so an empty answer is still a
  200 and the story stays about who may read.
  <br>It is also this repo's **first** userflow: two `@UserStory` methods in category
  `authentication`, browserless (an `Interactions` parameter and no `Flow`, so the transitive
  Playwright launches nothing), emitting `service/target/userstories/` — the proof doubling as
  documentation, network diagram included. **The diagram is observed, not narrated**: `Interactions`
  records notes only, the framework's `NetworkTaps.restAssured` taps what a story sends *into* this
  service, and `MockIdp`'s recording is registered as a cumulative `NetworkCapture.source` for what
  this service sent *out*. That tap was a hand-copied `StoryNetworkFilter` beside the IT until
  2026-08-29; the framework ships it now, idempotent per service, so the local copy is **deleted**
  and every story class installs the same one from its own `@BeforeAll`. The framework drains both
  at story end, which is why the two stories are `@Order`ed — a cursor attributes each recorded
  request to exactly one story, so the startup JWKS fetch lands in whichever story drains first and
  that must be the story about it. The class orderer is installed the one way Quarkus
  permits, `junit.quarkus.orderer.secondary-orderer` in this module's test properties; a local
  `junit-platform.properties` hard-fails surefire. The **userflow half of the QA phase composed
  from `.config/qits/release.yml`** is what regenerates and publishes them as the docs
  bundle `@userflows/qits-ci`, versioned by the fold's merged sha — it was a separate
  `ci-event-userflows.yml` until the single QA pipeline absorbed it. That step gates like every
  other step now (ticket 9441bc6e): a red userflow round is a red release-request run.
  <br>**`skipITs` stays `true` and this IT does not flip it.** Every IT binds to the same failsafe
  run (the three docker-backed `extended` gates that once shared it went with the in-process
  executor), so the opt-in is per-run and per-class,
  `-DskipITs=false "-Dit.test=TokenValidationBootstrapIT,BuildExecutionIT,BuildTriggerIT"`, which is
  exactly what the pipeline passes (note **commas**, never a plus) and what also keeps
  `CiPackagedSurfaceIT` out of a run that is about the stories.
- **`stories/` is the rest of the userflow catalogue, and it is where this service's own domain
  flows are documented.** Four stories in three categories, all against the same launched artifact
  and all browserless:

  | class | story | category |
  |---|---|---|
  | `stories/build/BuildTriggerIT` | A release event triggers a build | `builds` |
  | | An event nobody declared an interest in builds nothing | `builds` |
  | `stories/build/BuildExecutionIT` | A build step connects to qits-ci and streams its output | `builds` |
  | | An operator reads a finished build's transcript | `operations` |

  Five things about them are worth knowing before adding a sixth:

  - **Four planes are tapped and every one of them is passive.** `NetworkTaps.restAssured` draws
    what a story sent in; `stories/support/StoryGitHost` reads the stub git host's own request log
    back as `qits-ci -> qits-githost` edges; and the two sockets — for which the framework ships no
    tap — are instrumented at the call sites with `NetworkCapture.observe`, `socket` for the dial and
    `event` per frame in whichever direction it was pushed: the control socket in
    `stories/support/StoryDaemon`, and the runner socket in `stories/support/StoryRunner`, a runner
    played over the real `/ci/runners/socket` (a seeded runner row, a MockIdp bearer with role
    `qits:ci-runner`, a `Hello` carrying the `qits.ci.runner.self-update=false` label, then
    `Reserve`→`Take`, `Launch`→`Launched`, `Reap`→`Reaped`, `Released`). It replaced
    `stories/support/MockContainers`, the orchestrator stand-in, in qits-506. The file-backed
    taps register a **floor** at their first `install()` and are cumulative and
    prefix-stable, which is what the framework's per-source cursor requires: a skipped line is never
    in the list, so skipping cannot shift an earlier story's slice while moving a floor would.
  - **The story learns its launch id and the run's token the way a container learns them.**
    `StoryRunner` holds the `Launch` it was sent, so `StoryDaemon` reads `QITS_CI_DAEMON_ID`,
    `QITS_TOKEN` and `QITS_TOKEN_SUBJECT` out of the workload spec qits-ci actually sent. Nothing
    reads the host's launch table, which is what makes an admitted dial a measurement of the whole
    path rather than a fixture with privileged access. The launched qits-ci can mint that token
    because the shared profile switches its qits oidc client on and points it at a stub of
    qits-idp's commissioning surface (`stories/support/StoryRunTokens`).
  - **There is no edge in the suite, so a story daemon arrives as what the edge forwards.**
    `CiDaemonSocket`'s `@RolesAllowed` is enforced at the HTTP *upgrade*. `StoryDaemon` presents a
    JWT minted by the mock idp — `sub` the token's subject, role `qits:ci-run` — and names its launch
    in its `Hello`. The real binary presents the RAW token, which qits-ci does not read, so
    `CiDaemonPinIT` puts `stories/support/StoryEdge` in between: it takes the binary's bearer and
    forwards the connection as that JWT, relaying frames both ways.
  - **Fixture setup is invisible to both taps by construction.** `stories/support/StoryOrigin`
    writes a bare repository onto the stub git host's disk with a plain `ProcessBuilder` `git` — no
    RestAssured call and no HTTP to the stub — so nothing a story did *not* do appears in its
    diagram. It also waits out `HttpGitHostRepoListing`'s five-second candidate cache after
    publishing: a repository created inside that window is one the engine has not heard of, and the
    event that should have matched it comes back `runIds: []` with nothing skipped, which reads
    exactly like a correct verdict.
  - **The repository ids are fixed and readable, never UUIDs.** `Labels` rewrites a whole UUID path
    segment to `{id}`, so a generated id would make every git-host label read
    `GET /git/{id}/tree/main` and say nothing about which repository ci went to. `StoryOrigin`
    deletes and recreates, so fixed still means known.

  What is out of reach here: a real step image, a real `qits-ci-daemon` binary and a real docker
  daemon. The docker half is the runner's and is tested in qits-ci-runner-daemon; the `extended`
  gates that drove it from here (`CiDaemonGateIT`, `CiDaemonHandshakeIT`) went with the in-process
  executor. What these stories
  add is that they need none of them, so the flow is documented on every ordinary build.
- **`daemonhost/QitsCliPinIT` is the qits CLI pin's gate, and it is a plain failsafe `*IT`.** The
  `qits` CLI every composed release step on the platform runs is a pinned dependency now
  (`eu.wohlben.qits:qits-platform-access-cli-binary`; the root pom's property, `StepContainerSettings`
  injecting `PlatformAccessCliBinary.VERSION` as `$QITS_ARTIFACTS_CLI_VERSION`), and this is what
  makes the pin mean something: it downloads that exact coordinate from the REAL artifacts store,
  asks that binary, offline, for the usage of `artifacts publish sbom submit` and
  `artifacts publish exists` and asserts every option the postlude passes, then composes a real
  release-phase step through `CiReleaseComposer` and runs it under `bash` against a scratch git
  origin and a stub store serving a recording stand-in for the CLI, asserting the calls it made.
  The stub is put in place of `CiReleaseComposer.CLI_DOWNLOAD_BASE` in the composed TEXT, on both
  the curl and the wget arm: the download host is code, so no variable a step is handed can move it,
  and the test's seam is one only its own copy of the script has.
  It no longer makes the pinned binary publish (qits-731): a CLI from qits-731 on derives every
  address from `$QITS_DOMAIN` and reads no URL variable, so a live publish could only reach the
  real, immutable sbom store. A missing coordinate is a **failure naming it**, never a skip; the one
  `assumeTrue` covers an artifacts origin configured to nothing and is defensive rather than a
  supported mode. **Not a `@QuarkusIntegrationTest`**: nothing in the assertion needs the
  application, and a second `@TestProfile` would be a second launched qits-ci for no assertion a
  plain JUnit class cannot make — `WorkspaceDaemonPinIT` over in qits-workspaces makes the same
  judgement in the same words. It is named in the `-Dit.test` comma list of
  `.config/qits/release.yml`'s `release-request:` slot, needs no docker, and needs qits-artifacts reachable through
  `$QITS_MAVEN_REPOSITORY_URL` — which that recipe's verify step already exports. A class not named
  there never runs at all, silently, so the two move together.
  <br>The pinned binary's GET from the real store is the only contact with the platform, and it is
  a read: "the pinned version exists" is the assertion, while writing an SBOM into the platform's own
  immutable sbom store at a coordinate nobody released would be permanent litter. The child
  processes' environment is stripped of every ambient `QITS_` variable before the ones a step really
  gets are set — `QITS_DOMAIN` to an unresolvable `.invalid` domain — for `WorkspaceDaemonPinIT`'s
  measured reason: a pin test whose result depends on where it runs proves nothing about the pin.
- **`daemonhost/QitsCliPinIT` is the qits CLI pin's gate, and it is a plain failsafe `*IT`.** The
  `qits` CLI every composed release step on the platform runs is a pinned dependency now
  (`eu.wohlben.qits:qits-platform-access-cli-binary`; the root pom's property, `StepContainerSettings`
  injecting `PlatformAccessCliBinary.VERSION` as `$QITS_ARTIFACTS_CLI_VERSION`), and this is what
  makes the pin mean something: it downloads that exact coordinate from the REAL artifacts store,
  asks that binary, offline, for the usage of `artifacts publish sbom submit` and
  `artifacts publish exists` and asserts every option the postlude passes, then composes a real
  release-phase step through `CiReleaseComposer` and runs it under `bash` against a scratch git
  origin and a stub store serving a recording stand-in for the CLI, asserting the calls it made.
  The stub is put in place of `CiReleaseComposer.CLI_DOWNLOAD_BASE` in the composed TEXT, on both
  the curl and the wget arm: the download host is code, so no variable a step is handed can move it,
  and the test's seam is one only its own copy of the script has.
  It no longer makes the pinned binary publish (qits-731): a CLI from qits-731 on derives every
  address from `$QITS_DOMAIN` and reads no URL variable, so a live publish could only reach the
  real, immutable sbom store. A missing coordinate is a **failure naming it**, never a skip; the one
  `assumeTrue` covers an artifacts origin configured to nothing and is defensive rather than a
  supported mode. **Not a `@QuarkusIntegrationTest`**: nothing in the assertion needs the
  application, and a second `@TestProfile` would be a second launched qits-ci for no assertion a
  plain JUnit class cannot make — `WorkspaceDaemonPinIT` over in qits-workspaces makes the same
  judgement in the same words. It is named in the `-Dit.test` comma list of
  `.config/qits/release.yml`'s `release-request:` slot, needs no docker, and needs qits-artifacts reachable through
  `$QITS_MAVEN_REPOSITORY_URL` — which that recipe's verify step already exports. A class not named
  there never runs at all, silently, so the two move together.
  <br>The pinned binary's GET from the real store is the only contact with the platform, and it is
  a read: "the pinned version exists" is the assertion, while writing an SBOM into the platform's own
  immutable sbom store at a coordinate nobody released would be permanent litter. The child
  processes' environment is stripped of every ambient `QITS_` variable before the ones a step really
  gets are set — `QITS_DOMAIN` to an unresolvable `.invalid` domain — for `WorkspaceDaemonPinIT`'s
  measured reason: a pin test whose result depends on where it runs proves nothing about the pin.
- `CiDaemonSocketTest` drives the real socket with a real WebSocket from `FakeCiDaemon`, an in-JVM
  dialler framing the real protocol exactly as the binary does. The host cannot tell it from a
  container, which is the point: admission, framing, dispatch and the blocking bridge are all
  provable with no docker and no published binary, and only the round trip through a real image is
  left out (the runner's suite owns the real `docker run`).
  <br>**It dials as what the edge forwards**: `X-Qits-User: <the run token's subject>` /
  `X-Qits-Roles: qits:ci-run`, which the forward-auth mechanism reads into an identity, and then
  names its launch with `hello(daemonId)`. There is no launch header and no secret (qits-515). An
  identity is needed at all because websockets-next enforces `@RolesAllowed` at the **upgrade**;
  never assume a socket assertion that passes in TEST mode passes against the artifact.
  <br>**A REFUSED dial can be over before the fixture is listening, and that was a live flake.** The
  two unauthorized cases close 1008 from `@OnOpen`, microseconds after the handshake, while
  `FakeCiDaemon`'s constructor is still installing its handlers on the test thread — and Vert.x
  answers every handler setter on a closed socket with `IllegalStateException: WebSocket is closed`.
  So the constructor handles both orderings (check `isClosed()`, and catch the one that slips between
  the check and the setter) and reads the close code off the socket, because a `closeHandler`
  registered after the close never fires. Under load it failed maybe one run in three; do not undo
  the guard on the grounds that the tests look green.
- **`CiDaemonHandshakeIT` and `CiDaemonGateIT` went with the in-process executor (qits-506)**, as did
  `CiDaemonLauncherContainersTest`, `CiDaemonStepRunnerContainersTest` and `StubContainers`: they drove
  the qits-containers path, which no longer exists. A real container, a real `docker run` and the
  host-gateway route back are the runner's, tested in qits-ci-runner-daemon. One lesson from those
  gates outlives them and is load-bearing in `BOOTSTRAP`: **the daemon fetch is retried** (10 attempts,
  12 s apart, which is why `qits.ci.daemon-register-timeout-seconds` is 180). Measured 2026-09-15 01:05
  UTC, run `974b5c0b-cc6d-4068-8baa-1108db86472c`: `wget: can't connect to remote host (10.0.1.237):
  Connection refused` 7 seconds into a qits-artifacts `stop-first` redeploy, which rejected release
  request `71b2c572-ab57-4359-8644-5e4973c25ec2`. A long-lived service that deploys stop-first has a
  refusal window on every deploy, so do not take the retry out.
- **Both `FakeCiStepRunner`s are scripted-event fakes, and neither performs a step.** A test
  declares the chunks a step "prints" and the `StepResult` it ends with; the fake replays that
  against the listener and returns it. No processes, no `bash`, no clone. The service module's copy
  used to be a deliberately *honest* fake that cloned and ran the script as host processes — that
  died with the approach it modelled, because a fixture that keeps executing repository code keeps
  the retired approach alive in the test sources after it left the main ones. Real step semantics
  against a real container are the runner's to prove, in qits-ci-runner-daemon. The two fakes are
  duplicated on purpose — the modules do not share a test classpath, and they differ in kind: the
  `ci` module's implements `CiRunnerStepRunner` (it absorbed `FakeRunnerStepRunner`) and holds the
  runs its `SuiteRunner` reserves; the service module's is a `@Mock CiRunnerStepRunner` that scripts
  the runs its own `SuiteRunner` holds and delegates everything else to the real `RunnerStepRunner`.
  Both copies carry a `during(stepIndex, …)` hook: it runs something on the driver thread *while* a
  step is executing, which is how a cancellation arriving mid-step is staged with no sleep and no
  race about when "mid-step" is. It is also how `QUEUED` is staged at all — the suite runner has one
  slot, so a run parked inside its first step really does hold the next one in the queue, and both
  states are then real at one instant the test controls. That is why the service module's
  copy grew the hook too: `RUNNING` and `QUEUED` had to be observable over HTTP, which is where the
  SPA sees them.
  <br>**The service module's copy also feeds `CiStepRelay`, and that is not it performing a step.**
  The relay is the *transport's* bookkeeping — which step, when it was handed over, what has come
  back — and that fake stands in for the transport, so a suite whose fake left it empty could not
  see the `live` object at all and every assertion about it would be made against a hand-wired relay
  instead of against the read surface. It makes the four calls the real step seam makes around a
  step, in its order (`begin`, `started`, `append` per chunk, `drop` on `runClosed`), and the
  `during` hook runs *after* the stamp — what it stages is the middle of a step, and a step the host
  has not handed over yet is the setup window rather than the state under test.
- **The candidate list is proven at three levels, and each one can only say its own thing.**
  `HttpGitHostRepoListingTest` is plain JUnit against a real server on a real socket: the url shape,
  the id filter, the cache, and every way the read can fail answering the *empty* set rather than
  throwing. `ListedAndKnownCiReposTest` is a `@QuarkusTest` in the `ci` module, because the union's
  whole content is which beans it composes — `KnownCiRepos` by its own type past its `@DefaultBean`,
  the port through an `Instance` — and a hand-wired instance would prove none of it. And
  `CiManualTriggerTest`'s last case is the production gap itself: a repository seeded onto the git
  host with a trigger file, asserted to have **no run row**, firing a run off a hand-supplied event. Only the whole engine can show that, which is why it lives there and not
  beside the listing.
  There is a `FakeGitHostRepoListing` in each module's test sources, duplicated for the reason both
  `FakeCiStepRunner`s are. The service module's is a `@Mock`, so the real client is out of the way of
  every other test; the `ci` module's is an ordinary bean, because that module ships no
  implementation of the port at all and this is what makes it resolvable there. **Both default to
  empty**, which is not a neutral default but the interesting one: an empty listing is exactly what an
  unreachable git host answers, so the fallback needs no failure to stage.
- **Two classes in this repo run with the event bus on, and they share one profile deliberately.**
  Everything else inherits the shipped `%test` darkness, so "the suite dials nothing" is the default
  rather than an arrangement each test makes; `BuildSuccessfulPublishTest` turns it back on through a
  `QuarkusTestProfile` and points it at its own `StubEventsServer`, and
  `CiEventTriggerCausationTest` reuses **that same profile class** rather than declaring an identical
  one — a second `@TestProfile` is a second Quarkus start, and these two want the same application.
  The second class is the trigger engine's bus half: a real frame through `EventDispatcher`, a real
  `git ls-tree` of a real bare, and the `parentId` on the PUT the triggered run publishes — the
  platform's first automatic causation edge, which nothing in the `ci` module can see. It is where
  the release **fan-out** is proved too, and that belongs here rather than at the seam: N
  `SoftwareRelease` events under one parent is what shows the stamp is a non-consuming read rather
  than something the first publish spends, and their payload bytes are asserted whole because they
  are the contract every downstream release pipeline reads. It is also
  where the stub's recorded **subscribe frame** is asserted to be `["*"]`; subscribes are not cleared
  by `reset()`, because there is one per connection and the connection outlives every test method.

  The stub they share is a trimmed second copy of the eventstream module's, duplicated for the
  reason both `FakeCiStepRunner`s are (the modules do not share a test classpath, and a test-jar to
  bridge forty lines is worse). `BuildSuccessfulPublishTest` drives a real run to `SUCCESS` through a
  trigger file it commits and fires by hand, and asserts the *wire* contract the other side was built
  against: one PUT per green
  run, a v4 UUID in the path, `name` as the signature, and the run's coordinates in the canonical
  payload. Retries, the outbox and the three-way PUT semantics belong to the eventstream suite; the
  round trip through a real qits-events belongs to the platform.

  **One thing bites in both, and it is the shared candidate list.** Every repository either class has
  ever seeded is a candidate for every frame the trigger engine evaluates, for the life of that
  Quarkus instance. So a trigger file in a test fixture must select something **unique to the
  repository that committed it**, or one test method's event fires an earlier method's repository and
  "exactly two runs, exactly two publishes" stops being a statement about the test making it.
- **`api/CiQueueSurfaceTest` is the queue's read surface, and it shares the default application on
  purpose.** A `@TestProfile` is one extra Quarkus boot and this class needs no configuration
  nothing else has, so it declares none — the rule this repo states for `MachineGuardTest.GateOn`
  and `TokenValidationBootstrapIT`'s profile, applied the other way round. Four cases: the claim
  order with its positions and an ETA chain asserted *exactly* (one build slot in the suite, so the
  queue is plain addition and each start equals the previous finish to the millisecond); a run
  behind an unpredicted one reporting `RUN_AHEAD_HAS_NO_PREDICTION` with null ETAs, which is the
  "say so rather than skip" rule and the one most likely to be quietly dropped; a listing carrying a
  recorded step's boundaries with its output null while the single-run read still carries it; and
  `phase` on a release-request run, a release run and an ordinary one.
  <br>**Every case waits the queue quiet first, and that is load-bearing rather than hygiene.** The
  suite shares one application, so a run another class left in flight is a real member of the queue
  under test — and if it carried no prediction it would poison every ETA behind it with
  `RUN_AHEAD_HAS_NO_PREDICTION`, which is *correct behaviour* and a failed assertion. Waiting for an
  empty queue is what makes the positions absolute and the ETA chain assertable exactly rather than
  approximately. The states themselves are staged the way `CiPipelineBoundaryTest` established —
  `FakeCiStepRunner.during` parks the suite runner's one slot inside a step — and the third case parks inside
  step **one**, so step zero really has a row and the assertion is that a listing *drops* an
  output rather than that it had none.
  <br>The arithmetic itself is not here: `CiQueueForecastTest` and `CiRunOrderingTest` in the `ci`
  module stage overrunning runs, shrunk slot counts, cycles and every unknown against rows built in
  memory, with no database and no runner — which is only possible because both classes are pure and
  take `now` as a parameter.
- `CiPipelineBoundaryTest` is the whole loop at the seams this repo owns: a repository committing a
  trigger file on `main`, an event supplied through `POST /ci/api/events/trigger`, and the run read
  back over HTTP. It **started at a push** until 2026-09-05, when the intake retired; what is left of
  that starting point is one pinning, `anOrdinaryPushIsConsumedAndTriggersNothing`, which hands the
  generic engine a real `SCMPublishCommit` and asserts no run appears. `bus/ScmPushFrames` builds
  that frame from the real record, so a payload change is a compile error rather than a suite that
  keeps passing against bytes nobody sends. Assertions about which refs the git host announces — and
  about the tag and delete events nothing here subscribes to — belong to qits-githost.
- `CiRestartReconciliationTest` is the boot half of the same story: two runs left `RUNNING` by a
  process that died with no shutdown path, then `CiRunService.sweepInterrupted`, then the leftover
  push run asserted `FAILED` — no live deployment writes one any more, and a successor still has to
  settle the ones a predecessor left — and the event run asserted **re-run by a runner** from its own
  snapshot (`SuiteRunner` reserves it after the sweep announces the backlog, and its `runnerId` is the
  suite runner's), which is a stronger statement than the intermediate `QUEUED`. It is a plain
  `@QuarkusTest` and needs no docker. It used to drive a second startup observer too —
  `CiDaemonLauncher.destroyAllOwned`, the owner-scoped container reap ordered before the sweep
  (`BootReconciliationOrderTest` held that order) — and that half went with the in-process executor:
  a step container belongs to the runner that started it, and the runner's own boot sweep removes
  what a previous life of it left. It sits in `control` because `sweepInterrupted` is package-private
  there.
