package eu.wohlben.qits.ci.bus;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * qits-projects' announcement that a repository answers to a new public name, transcribed here as
 * the record this service binds its payload to.
 *
 * <p><b>A local copy rather than a dependency, and that is the platform's standing answer.</b>
 * qits-projects publishes no vocabulary jar — the same measurement that keeps {@code SCMRelease} a
 * transcription in {@code ScmReleaseContractTest}, and qits-projects says so in this record's own
 * javadoc over there: a jar the platform Maven registry does not serve resolves from a developer's
 * {@code ~/.m2} and fails in a release pipeline's step container. So the component list below is
 * hand-kept, and {@code RepositoryRenamedContractTest} is what keeps it honest — it names the source
 * file and runs this record through the real {@code CanonicalJson}, so every rule about the wire form
 * stays the library's and only the list of names is a person's to maintain.
 *
 * <p><b>Bound rather than walked, unlike every other foreign payload here.</b> {@code
 * ScmReleaseListener} and the trigger engine read theirs with {@code readTree} and owe no
 * native-image metadata for it; this one is a five-field record whose fields are all read, so binding
 * it says what it is worth saying and costs one entry in {@link EventWireReflection}. That entry is
 * not optional: {@code CanonicalJson} builds its own {@code ObjectMapper}, so nothing in the build
 * step can see that this type crosses the wire, and an unregistered record in the binary binds to a
 * component list Jackson cannot discover.
 *
 * <p><b>The components are the publisher's, in the publisher's order.</b> {@code eventId} is one of
 * them there and is one here, even though nothing on this side ever reads it: the canonical mix-in
 * hides every {@link QitsEvent} accessor by signature, so it is absent from the payload in both
 * directions and a bound instance simply mints a fresh one. Transcribing it anyway is what makes the
 * contract test's "the envelope's own fields are not in the payload" assertion mean something about
 * the publisher's record and not just about a shape this repository chose.
 *
 * <p><b>{@code renamedAt} is both a component and the event's {@code occurredAt}</b>, exactly as it
 * is over there — the rename's own commit time, not the moment the announcement was made. It is
 * therefore a payload key <em>and</em> an envelope field, which is not a duplication to tidy up: the
 * accessor is hidden by the mix-in, so the key is the only place the value reaches the wire from this
 * record's own data.
 */
public record RepositoryRenamed(
    UUID eventId,
    String projectId,
    String repositoryId,
    String oldName,
    String newName,
    Instant renamedAt)
    implements QitsEvent {

  public RepositoryRenamed {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  @Override
  public Instant occurredAt() {
    return renamedAt;
  }
}
