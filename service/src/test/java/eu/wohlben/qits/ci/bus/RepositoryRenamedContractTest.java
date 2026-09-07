package eu.wohlben.qits.ci.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * <b>The name and the five fields the rename repair is written against, checked against the wire form
 * they have to match.</b>
 *
 * <p>{@code RepositoryRenamedListener} recognises the event by NAME and binds its payload to {@link
 * RepositoryRenamed}, a record declared in this repository. Nothing in either half is derived from
 * qits-projects at build time, so exactly one thing can go wrong: the publisher renames the event or
 * one of its components, this service compiles, and every later rename is bound into a record whose
 * fields are quietly null — which settles as poison, one WARN per rename, and leaves the very rows
 * this listener exists to repair stale.
 *
 * <h2>Why a transcription and not a dependency</h2>
 *
 * <p><b>qits-projects publishes no vocabulary jar</b>, and says so in this record's own javadoc over
 * there: nothing consumed it until now, and a jar the platform Maven registry does not serve would
 * resolve here from a developer's {@code ~/.m2} and fail to resolve in the release pipeline's own
 * step container. That is the same measurement {@code ScmReleaseContractTest} records for {@code
 * SCMRelease} (2026-08-12, {@code nothing is deployed}), and the standing answer both times is the
 * same: the consumer keeps a local record and a test keeps the local record honest.
 *
 * <p>So this is {@code ScmReleaseContractTest}'s mechanism, one step tighter. There the record lives
 * in the test, because the listener walks the payload with {@code readTree} and never binds it. Here
 * the record is production code — {@code service/…/bus/RepositoryRenamed} — because the listener does
 * bind it, so what this file transcribes is not a private copy but <em>the</em> copy, and the
 * assertions below are about the class the binary ships.
 *
 * <h2>The source, named</h2>
 *
 * <p>{@link RepositoryRenamed} is a hand-kept copy of the component list of {@code
 * components/qits-projects/qits-projects-service/service/src/main/java/eu/wohlben/qits/projects/bus/RepositoryRenamed.java},
 * the record qits-projects publishes from its repository-rename {@code PATCH}. It is <b>not</b> a
 * fixture of expected JSON: the bytes below are produced by {@link CanonicalJson}, the same
 * serializer the real publisher runs the real record through, so every rule about the wire form —
 * alphabetical keys, {@code NON_NULL} inclusion, the {@code QitsEvent} accessors the mix-in hides —
 * stays the library's rather than something this file guessed. What a person keeps in step is one
 * list of names.
 *
 * <p><b>Read that as the standing instruction it is:</b> a change to that record in qits-projects is
 * a change to this one, in the same campaign. A rename that lands there and not here leaves this
 * suite green and the repair dead — which is the one failure this file cannot prevent and is why it
 * says so out loud.
 *
 * <p>The canonical bytes this produces are also what {@link DurableBusConsumptionTest} drives through
 * the real listener and the real tables, so the field names are proved to be read rather than merely
 * to be present.
 */
public class RepositoryRenamedContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** The rename's own commit time, which is also this event's {@code occurredAt}. */
  static final Instant RENAMED_AT = Instant.parse("2026-09-07T09:14:02Z");

  /** One rename, as qits-projects publishes it. */
  static RepositoryRenamed renamed(
      String projectId, String repositoryId, String oldName, String newName) {
    return new RepositoryRenamed(
        UUID.randomUUID(), projectId, repositoryId, oldName, newName, RENAMED_AT);
  }

  /** The canonical payload of one rename — the bytes a frame carries. */
  static String canonicalPayload(
      String projectId, String repositoryId, String oldName, String newName) {
    return CanonicalJson.payload(renamed(projectId, repositoryId, oldName, newName));
  }

  /**
   * The signature is the simple class name, so the transcribed record has to be spelled exactly as
   * the publisher spells it — and the listener has to subscribe to that same string. Asserted as one
   * chain, because two of the three agreeing proves nothing about the third.
   */
  @Test
  public void theEventNameThisListenerSubscribesToIsTheOneTheEventRidesUnder() {
    assertEquals("RepositoryRenamed", RepositoryRenamed.class.getSimpleName());
    assertEquals(RepositoryRenamed.class.getSimpleName(), RepositoryRenamedListener.EVENT_NAME);
    assertEquals(
        Set.of("RepositoryRenamed"),
        Set.of(RepositoryRenamedListener.EVENT_NAME),
        "the signature the listener asks the log for is the publisher's class name");
  }

  /**
   * <b>The component list, which is the whole of the contract.</b> Exactly these five keys, no more
   * and no fewer: a component added over there and not here is a fact this service silently drops,
   * and a component this record has that the publisher does not binds to null on every arrival.
   *
   * <p>Asserted as the payload's key SET rather than field by field, because "no fewer" is the half a
   * per-field assertion cannot make.
   */
  @Test
  public void thePayloadCarriesExactlyTheFiveComponentsTheRepairIsWrittenAgainst() throws Exception {
    JsonNode payload =
        MAPPER.readTree(
            canonicalPayload(
                "qits", "qits-configuration-service", "qits-configuration-service",
                "qits-configuration-platform-service"));

    Set<String> keys = new java.util.LinkedHashSet<>();
    payload.fieldNames().forEachRemaining(keys::add);
    assertEquals(
        Set.of("projectId", "repositoryId", "oldName", "newName", "renamedAt"),
        keys,
        "the transcription and the publisher's record no longer have the same components");

    assertEquals("qits", payload.get("projectId").asText());
    assertEquals("qits-configuration-service", payload.get("repositoryId").asText());
    assertEquals("qits-configuration-service", payload.get("oldName").asText());
    assertEquals("qits-configuration-platform-service", payload.get("newName").asText());
    assertEquals(RENAMED_AT.toString(), payload.get("renamedAt").asText());
  }

  /**
   * The two the payload must NOT carry, which is why the listener never reads them off the bound
   * record.
   *
   * <p>{@code eventId} and {@code occurredAt} are {@code QitsEvent}'s own accessors and the canonical
   * mix-in hides every one of them by signature — identity and time travel in the envelope. {@code
   * renamedAt} IS this event's {@code occurredAt} over there, so this is also the assertion that the
   * override costs the payload nothing: the value reaches the wire once, under the component's name.
   */
  @Test
  public void theEnvelopesOwnFieldsAreNotInThePayload() throws Exception {
    JsonNode payload = MAPPER.readTree(canonicalPayload("qits", "r-1", "old", "new"));

    assertFalse(payload.has("eventId"), "identity travels in the envelope, never in the payload");
    assertFalse(payload.has("occurredAt"), "and so does the timestamp");
    assertTrue(payload.has("renamedAt"), "which is why the rename's own time is a component");
  }

  /**
   * The bind, both ways, through the serializer the publisher uses. This is what {@code
   * EventWireReflection}'s new entry is there to keep working in the binary, and the round trip is
   * what makes the field names above load-bearing rather than decorative.
   *
   * <p>{@code eventId} comes back fresh, exactly as {@code BuildSuccessful}'s does: the mix-in hides
   * it on the way out, so there is nothing to restore and the compact constructor mints one.
   */
  @Test
  public void aRenamePayloadBindsBackToTheRecordTheListenerReads() {
    RepositoryRenamed out =
        renamed("qits", "qits-configuration-frontend", "qits-configuration-frontend",
            "qits-configuration-platform-frontend");

    RepositoryRenamed back =
        CanonicalJson.payloadTo(CanonicalJson.payload(out), RepositoryRenamed.class);

    assertEquals(out.projectId(), back.projectId());
    assertEquals(out.repositoryId(), back.repositoryId());
    assertEquals(out.oldName(), back.oldName());
    assertEquals(out.newName(), back.newName());
    assertEquals(out.renamedAt(), back.renamedAt());
    assertEquals(out.renamedAt(), back.occurredAt(), "occurredAt is renamedAt on this event");
    assertFalse(out.eventId().equals(back.eventId()), "identity is the envelope's");
  }
}
