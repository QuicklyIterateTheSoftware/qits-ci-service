package eu.wohlben.qits.ci.entity;

/**
 * Which phase of a release pipeline a run is — the QA phase or the publish phase — or, by being null
 * on the row, no phase at all.
 *
 * <p><b>A release is one pipeline of three phases and the release request is the pipeline.</b> P1 is
 * QA: a run at {@code release/<id>@mergedSha}, triggered by {@code ReleaseRequestChanged}, whose
 * verdict is what a person's approval is given against. P2 is Publish: a run at {@code
 * <version>@commitSha}, triggered by {@code SCMRelease}, which builds and pushes what the tag names.
 * P3 is Deploy and is qits-deployments' request rather than a run here, so it has no word in this
 * enum — a phase qits-ci does not execute is not a phase qits-ci can record.
 *
 * <p><b>There is no pipeline table and no pipeline id in this service, deliberately.</b> The release
 * request in qits-projects is the pipeline; this enum is qits-ci's entire share of the model, which
 * is that a run knows which phase it is, that word goes on the wire, and a rerun can be addressed by
 * phase rather than by a run id nobody over there holds.
 *
 * <p><b>The decision is the TRIGGER EVENT'S NAME and nothing else.</b> {@code ReleaseRequestChanged}
 * carrying a release request id is {@link #RELEASE_REQUEST}; {@code SCMRelease} carrying one is
 * {@link #RELEASE}; an event naming no request has no phase. Nothing reads {@code
 * CiRun#configPath} to decide it, which is what made the two rollouts independent while the fleet
 * was migrating to {@code release.yml}, and what still keeps a run from a repository's own {@code
 * ci-event-*.yml} recording its phase exactly as a composed one does.
 *
 * <p><b>Not {@code CiEventTriggerService.ReleasePhase}</b>, which is a different question with an
 * unfortunately similar name: that record and its {@code Verdict} answer "does this rev compose a
 * publish phase at all", a read of a repository's declaration made before any run exists. This is a
 * property of a run that was accepted.
 *
 * <p>It is stored as its own name ({@code @Enumerated(STRING)}) and it leaves this service as a
 * plain {@code String} — the wire vocabulary in {@code ci-events/} must not import this module's
 * storage model, the same rule {@code status} and {@code outcome} already ride.
 */
public enum CiRunPhase {

  /**
   * P1, QA: the run a {@code ReleaseRequestChanged} caused, at the tip of the request's backing
   * branch. Its verdict is about a fold nobody pushed, which is why {@code
   * CiRun#releaseRequestId} and not the sha is the handle anything addresses it by — and why a
   * spent one cannot simply be re-asked: the tag has been cut and {@code release/<id>} is gone.
   */
  RELEASE_REQUEST,

  /**
   * P2, Publish: the run an {@code SCMRelease} caused, at the released tag's own commit. Its
   * checkout is a ref that cannot move under it, so it is the phase a rerun is always safe for.
   */
  RELEASE
}
