# How a run actually runs — the picture

`README.md` is the contract; this file draws it. Since epic qits-1481 a run is **one CI lease on a
qits-system target**: every step of the run executes in the same `qits/build-images/ci-slot`
container, driven over one control WebSocket. There are no runners, no runner socket and no
container per step any more, and no step ever gets a docker socket.

| | the control WebSocket | the lease |
|---|---|---|
| what | `wss://ci.qits.<domain>/ci/daemon`, qits-ci's public name behind the platform edge (composed from `QITS_DOMAIN`) | `POST /system/api/leases` at qits-system: slot type `CI`, owner `qits-ci`, owner ref the run id |
| who opens it | the slot's **`qits-ci-daemon`**, dialling **out** through the edge; qits-ci never dials in | qits-ci, once per run, when the run is queued |
| what rides it | every step's script one way (`RunStep`), output chunks and lifecycle frames the other, and the run's `Close` | the slot's spec — image pinned by digest, environment, user `1001`, limits — and the lease's state |
| what it grants | "deliver data about this run", authenticated by the run's `ci-run` token; the launch is named in the daemon's first frame | one container on one target for as long as the run holds the lease |
| if it goes wrong | the step is recorded `NEVER_STARTED` / `CONNECTION_LOST` | a lease that ends `FAILED` or `EXITED` before the daemon said `Initialized` is the run's first step `LAUNCH_FAILED`, with the lease's own words |

```mermaid
flowchart LR
    ci["qits-ci<br/>(no docker socket)"]
    system["qits-system<br/>leases"]
    subgraph target["a qits-system target"]
        runner["qits-system-runner"]
        dockerd["dockerd"]
    end
    subgraph slot["one ci-slot container — the whole run"]
        daemon["qits-ci-daemon"]
        script["each step's script<br/>repo-controlled code"]
    end

    ci -->|"create / get / list / end lease<br/>(qits system bearer)"| system
    system -.->|"TargetLeaseChanged on qits-events"| ci
    system -->|"grants the lease to a target"| runner
    runner -->|"docker run / docker rm"| dockerd
    dockerd -.->|"starts the slot, user 1001, the spec's limits"| slot
    daemon -.->|"control WebSocket — dialled OUT, once per run<br/>RunStep in, chunks + StepFinished out, Close in"| ci
    daemon --> script

    classDef plain fill:#eef,stroke:#89c
    class ci,system,runner,dockerd,daemon,script plain
```

**One address plane.** The slot joins no docker network qits-ci knows of. Every address it is told is
the public name of a service, `https://<host>.qits.<domain>`, reached through the platform edge with
the run's token: qits-ci's daemon socket, the git host it clones from, the registry, the mirror,
qits-workspaces.

**No docker in a step.** A step that declares `docker: true` is `CONFIG_ERROR` with `docker steps are
not supported: steps run in qits/build-images/ci-slot without Docker` — the run is recorded with that
step failed, and nothing is leased. A step's `image:` is ignored, and the first step's output says so
once: `step images are ignored: every step runs in qits/build-images/ci-slot`. `build: true` stays:
an image build is the target's builder's (`$BUILDKIT_HOST`, which the target sets and the spec may
not).

## The whole flow, once

```mermaid
sequenceDiagram
    autonumber
    participant Proj as qits-projects
    participant Bus as qits-events
    participant Git as qits-githost
    participant Ci as qits-ci
    participant Idp as qits-idp
    participant Sys as qits-system
    participant Tgt as target (qits-system-runner)
    participant Slot as ci-slot container

    Proj->>Bus: ReleaseRequestChanged{repository, backingBranch, mergedSha}
    Bus-->>Ci: the frame (durably; a catch-up sweep reads it back)
    Ci->>Git: the pipeline files at the event's revision — no clone
    Ci->>Ci: accept: the run row QUEUED, the ci-slot image pinned by digest
    Ci->>Idp: POST /idp/api/tokens {contextKind: ci-run, contextId: runId}<br/>BEFORE the lease, so nothing can be granted that has no token
    Ci->>Sys: POST /system/api/leases {CI, runId, priority, spec}<br/>spec: image@digest, env (no per-step parts), user 1001, qits.ci.slot.* limits
    Ci->>Ci: record lease_id and the launch (daemon id, token subject) on the row
    Sys->>Tgt: grant, by priority desc then requested_at
    Tgt->>Slot: docker run — the slot starts
    Sys-->>Ci: TargetLeaseChanged{RUNNING, targetId} (and every 30 s reconcile)
    Slot->>Slot: bootstrap: fetch the pinned qits-ci-daemon with $QITS_TOKEN, exec it
    Slot-->>Ci: dials the control WebSocket as the run's token, Hello{daemonId}
    Note over Ci: the dial is also a cue: qits-ci reads the lease and claims the run<br/>(RUNNING, target_id) if no event got there first
    Slot->>Git: clone with the token, checkout $QITS_CI_SHA
    Slot-->>Ci: Initialized

    loop every step, on the same connection
        Ci-->>Slot: RunStep{correlationId, stepIndex, script, timeoutSeconds, env{QITS_CI_STEP_INDEX}}
        Slot-->>Ci: StepChunk … — the bounded relay behind GET /ci/api/runs/{runId}'s `live`
        Slot-->>Ci: StepFinished{stepIndex, exitCode, timedOut}
        Ci->>Ci: write the step row ONCE, already terminal
    end

    Ci-->>Slot: Close — the daemon exits
    Ci->>Sys: DELETE /system/api/leases/{leaseId}
    Ci->>Idp: DELETE /idp/api/tokens/{tokenId}
    Ci->>Bus: BuildSuccessful on SUCCESS; BuildFailed with the terminal word otherwise
```

Four things the diagram is deliberately precise about:

- **One lease, one container, one connection per run.** The clone happens once, and every step
  sees what the steps before it left in the worktree. The `seq` of the output chunks runs across
  the whole lease, and the daemon ignores a `RunStep` naming an index it has already run, which is
  what makes a resend after a reconnect safe.
- **The script arrives as a reply.** qits-ci initiates nothing toward a container and never touches
  docker. The first `RunStep` answers the daemon's `Initialized`; each later one follows the previous
  `StepFinished`.
- **The token comes first and goes last.** It is minted before the lease is asked for and deleted
  when the run closes, or as soon as the lease is seen to have ended, whichever comes first.
- **The row is written once, terminal.** While a step runs it has no row; `live` is what makes that
  legible.

## When the lease does not get that far

- **qits-system does not answer** the create: nothing is recorded, the run stays `QUEUED`, and the
  reconcile asks again.
- **The lease ends `FAILED` or `EXITED` before `Initialized`** (an image the target refused, a spec
  the policy rejected, a slot that died booting): the run is claimed anyway and its first step is
  `LAUNCH_FAILED` with the lease's detail, so the verdict names the cause.
- **A lease no live run names** (a run settled while its lease was queued, a previous process's
  leftovers) is ended by the reconcile once it is older than two minutes.
- **qits-ci restarts mid-run.** The lease outlives the process. The launch and the in-flight step are
  on the row (`lease_id`, `inflight_*`), the successor registers the launch again under its old id,
  the re-dialling daemon is admitted as that launch, and the run continues at the first step without
  a row — the in-flight one taken back, never started twice (qits-1192, kept for leases).
- **A queued run holding a lease a previous process asked for** has that lease ended and a fresh one
  requested, since its launch was never recorded in this process.

## Where a publishing step fits

A publishing step is a step like any other: its script calls `qits artifacts publish …` or drives
`buildctl` against `$BUILDKIT_HOST`, the target's builder, which pulls the bases through the mirror
and pushes to `registry.qits.$QITS_DOMAIN` with the run's token. The push is the step's exit code: a
failed push is a failed step is a failed run, so `SoftwareRelease` — one per published declaration,
announced by qits-ci only after the run is green and the release is real — keeps implying the
artifact exists.
