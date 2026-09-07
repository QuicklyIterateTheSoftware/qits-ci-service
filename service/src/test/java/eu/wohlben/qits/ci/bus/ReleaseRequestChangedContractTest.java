package eu.wohlben.qits.ci.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.ci.control.CiRunOrdering;
import eu.wohlben.qits.ci.control.CiRunService;
import eu.wohlben.qits.eventstream.QitsEvent;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * <b>The four strings a repository's QA pipeline is written against, checked against the wire form
 * they have to match.</b>
 *
 * <p>qits-projects maintains one backing branch per open release request — {@code release/<id>}, an
 * octopus merge of N sources — and publishes {@code ReleaseRequestChanged} on every successful
 * re-fold. That branch is written by the git host's merge primitive, which fires no {@code
 * post-receive} and therefore publishes no {@code SCMPublishCommit}: without this event the fold
 * exists and <b>nothing builds it</b>. So a repository's single QA pipeline selects this event and
 * checks the fold out:
 *
 * <pre>{@code
 * event: ReleaseRequestChanged
 * when:
 *   - repoName: { exact: <this repository> }
 * checkout:
 *   branch: backingBranch
 *   sha: mergedSha
 * }</pre>
 *
 * <p><b>Six literals</b> therefore have to agree with a record in another repository: the event NAME
 * ({@link CiRunService#RELEASE_REQUEST_EVENT_NAME}), the two checkout dot-paths, the field the run's
 * {@code release_request_id} column is read out of ({@link CiRunService#RELEASE_REQUEST_ID_FIELD}),
 * and — since the ordering campaign — the two fields the run QUEUE is ordered by ({@link
 * CiRunService#PRIORITY_FIELD} and {@link CiRunService#RELEASE_REQUEST_DOWNSTREAM_FIELD}, read onto
 * {@code ci_run.priority} and {@code ci_run.downstream_repos} and consumed by {@link
 * CiRunOrdering}). Nothing in this service binds the payload — the trigger engine subscribes to
 * {@code "*"} and walks a {@code JsonNode} — so nothing but this file would notice a rename.
 *
 * <p><b>The two halves fail differently and both matter.</b> A rename of the event or the checkout
 * paths costs a repository its QA run outright, loudly. A rename of the two ordering fields costs
 * nothing visible at all: an unreadable field is "unknown", unknown is a legitimate value with a
 * defined rank, and the only symptom is a queue that has quietly stopped being ordered. That is
 * precisely the class of regression a transcription exists to catch.
 *
 * <h2>Why this is a transcription and not a resolution against the real record</h2>
 *
 * <p>{@code ScmPublishTagContractTest} resolves its two strings against the real {@code
 * SCMPublishTag}, because {@code qits-githost-events} is a dependency of this module. That move is
 * not available here for the reason {@code ScmReleaseContractTest} states at length about
 * qits-workspaces: <b>qits-projects publishes no vocabulary jar</b>, deliberately and by its own
 * ruling — a jar the platform's Maven registry does not serve resolves from a developer's {@code
 * ~/.m2} and fails inside a release pipeline's own step container, which is a red release rather
 * than a guard. That repository's own note says the field list below "is the contract qits-ci builds
 * its local record against"; this is that record.
 *
 * <p>So the transcription below is the contract, and it is <b>not</b> a fixture of expected JSON:
 * the bytes are produced by {@link CanonicalJson}, the same serializer the real publisher runs the
 * real record through, so every rule about the wire form stays the library's. What a person keeps in
 * step is one list of component names.
 *
 * <p><b>Read that as the standing instruction it is:</b> a change to {@code
 * qits-projects-service/service/…/bus/ReleaseRequestChanged.java} is a change to this transcription,
 * in the same campaign. A rename that lands there and not here leaves this suite green and every
 * repository's release-request pipeline silently dead — which is the one failure this file cannot
 * prevent and is why it says so out loud.
 */
public class ReleaseRequestChangedContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** The dot-path a QA pipeline's {@code checkout:} names for the branch to clone. */
  static final String BACKING_BRANCH_FIELD = "backingBranch";

  /** The dot-path a QA pipeline's {@code checkout:} names for the commit to build. */
  static final String MERGED_SHA_FIELD = "mergedSha";

  /**
   * A transcription of qits-projects' {@code ReleaseRequestChanged} — its component list, its order
   * and its types, under the name it rides the bus under.
   *
   * <p><b>The NAME is transcribed too</b>, which is what makes the signature assertion below say
   * anything: a wire signature is the simple class name, so this record has to be spelled exactly as
   * qits-projects spells it. Only the shape matters otherwise, so the convenience constructor the
   * publisher uses is not copied; the canonical form is a function of the components and nothing
   * else.
   */
  record ReleaseRequestChanged(
      UUID eventId,
      String projectId,
      String repoId,
      String repoName,
      String releaseRequestId,
      String backingBranch,
      String mergedSha,
      Instant changedAt,
      String priority,
      List<String> downstreamTechnicalComponents)
      implements QitsEvent {

    @Override
    public Instant occurredAt() {
      return changedAt;
    }
  }

  /** One re-fold, as qits-projects would publish it — stating no priority, the shape every case
   *  written before the field existed produces. */
  static ReleaseRequestChanged changed(String repoId, String requestId, String mergedSha) {
    return changed(repoId, requestId, mergedSha, null);
  }

  /** The same, with the request's effective priority stated. */
  static ReleaseRequestChanged changed(
      String repoId, String requestId, String mergedSha, String priority) {
    return changed(repoId, requestId, mergedSha, priority, null);
  }

  /** The same again, with the downstream closure qits-projects resolved for the folded repository. */
  static ReleaseRequestChanged changed(
      String repoId,
      String requestId,
      String mergedSha,
      String priority,
      List<String> downstream) {
    return new ReleaseRequestChanged(
        UUID.randomUUID(),
        "qits",
        repoId,
        "qits-ci-service",
        requestId,
        "release/" + requestId,
        mergedSha,
        Instant.parse("2026-09-03T09:07:06Z"),
        priority,
        downstream);
  }

  /** The canonical payload of one re-fold — the bytes a frame carries. */
  static String canonicalPayload(String repoId, String requestId, String mergedSha) {
    return CanonicalJson.payload(changed(repoId, requestId, mergedSha));
  }

  /** The same, for a re-fold of a request that carries a priority. */
  static String canonicalPayload(
      String repoId, String requestId, String mergedSha, String priority) {
    return CanonicalJson.payload(changed(repoId, requestId, mergedSha, priority));
  }

  /** The same, for a re-fold that also carries the downstream closure. */
  static String canonicalPayload(
      String repoId,
      String requestId,
      String mergedSha,
      String priority,
      List<String> downstream) {
    return CanonicalJson.payload(changed(repoId, requestId, mergedSha, priority, downstream));
  }

  @Test
  public void theEventNameACiPipelineSelectsIsTheOneThisEventRidesUnder() {
    assertEquals(
        ReleaseRequestChanged.class.getSimpleName(), CiRunService.RELEASE_REQUEST_EVENT_NAME);
  }

  @Test
  public void theThreeFieldsAQaPipelineDependsOnAreInTheCanonicalPayload() throws Exception {
    JsonNode payload = MAPPER.readTree(canonicalPayload("r-1", "rr-42", "c".repeat(40)));

    // The two a `checkout:` names. Without both there is no truthful (branch, sha) pair and the
    // engine records no run at all — the fold would exist and nothing would build it.
    assertTrue(
        payload.has(BACKING_BRANCH_FIELD),
        "the canonical payload carries no " + BACKING_BRANCH_FIELD);
    assertEquals("release/rr-42", payload.get(BACKING_BRANCH_FIELD).asText());
    assertTrue(payload.has(MERGED_SHA_FIELD), "the canonical payload carries no " + MERGED_SHA_FIELD);
    assertEquals("c".repeat(40), payload.get(MERGED_SHA_FIELD).asText());

    // The one the run row's release_request_id is read out of. Losing it costs no run — it costs
    // every run of every release request its only stable handle, which is what cancel and retry
    // address work by.
    assertTrue(
        payload.has(CiRunService.RELEASE_REQUEST_ID_FIELD),
        "the canonical payload carries no " + CiRunService.RELEASE_REQUEST_ID_FIELD);
    assertEquals("rr-42", payload.get(CiRunService.RELEASE_REQUEST_ID_FIELD).asText());
  }

  @Test
  public void theRepositoryASelectionMatchesOnIsInTheCanonicalPayload() throws Exception {
    // `when: - repoName: { exact: … }` is what makes this event one repository's rather than the
    // whole catalogue's, and the engine's addressable alias reads repoName first and repoId after.
    JsonNode payload = MAPPER.readTree(canonicalPayload("r-1", "rr-42", "c".repeat(40)));

    assertEquals("qits-ci-service", payload.get("repoName").asText());
    assertEquals("r-1", payload.get("repoId").asText());
  }

  /**
   * <b>The ninth component, and the first one the run queue really reads.</b>
   *
   * <p>{@code priority} is the release request's effective priority — the maximum over the
   * priorities declared on its participating branches. It was transcribed here in the priority
   * campaign purely because the record grew it, with the note that "a QA run is not where the value
   * enters qits-ci" and "the queue is FIFO". <b>That is no longer true</b>: the queue is a claim loop
   * ordered by {@link CiRunOrdering}, {@code CiRunService.priorityOf} reads this exact field off this
   * exact event at accept time, and it lands on {@code ci_run.priority}.
   *
   * <p>So the spelling is pinned against the constant that reads it, and not only asserted to be
   * present: a rename in qits-projects now costs every release request its place in the queue
   * (silently — an unreadable field is "unknown", which is a rank rather than an error), and this
   * assertion is what turns that into a red suite instead.
   */
  @Test
  public void theRequestsEffectivePriorityIsInTheCanonicalPayloadUnderTheNameCiReadsIt()
      throws Exception {
    JsonNode payload = MAPPER.readTree(canonicalPayload("r-1", "rr-42", "c".repeat(40), "HIGHER"));

    assertEquals(
        "priority",
        CiRunService.PRIORITY_FIELD,
        "the field qits-ci reads a run's priority out of is spelled by this constant");
    assertTrue(
        payload.has(CiRunService.PRIORITY_FIELD),
        "the canonical payload carries no " + CiRunService.PRIORITY_FIELD);
    assertEquals("HIGHER", payload.get(CiRunService.PRIORITY_FIELD).asText());
  }

  /**
   * <b>The tenth component, and the other half of what the queue orders by.</b>
   *
   * <p>{@code downstreamTechnicalComponents} is the downstream closure qits-projects resolves for the
   * repository being folded — the repositories that will be renovated onto this one's release, named
   * nearest-first. qits-ci stores it verbatim on {@code ci_run.downstream_repos} and {@link
   * CiRunOrdering} sequences the queue by it: a queued run whose repository appears in this list
   * waits for the run that named it.
   *
   * <p>It is <b>added LAST</b>, after {@code priority}, and that position is part of the contract
   * rather than a style choice — a canonical payload is a function of the component list, so a
   * component inserted anywhere else would move bytes this file asserts are stable. That is what the
   * additive case below measures.
   */
  @Test
  public void theDownstreamClosureIsInTheCanonicalPayloadUnderTheNameCiReadsIt() throws Exception {
    JsonNode payload =
        MAPPER.readTree(
            canonicalPayload(
                "r-1",
                "rr-42",
                "c".repeat(40),
                "HIGHER",
                List.of("qits-ci-frontend", "qits-ci-service")));

    assertEquals(
        "downstreamTechnicalComponents",
        CiRunService.RELEASE_REQUEST_DOWNSTREAM_FIELD,
        "the field qits-ci reads a run's downstream closure out of is spelled by this constant");
    assertTrue(
        payload.has(CiRunService.RELEASE_REQUEST_DOWNSTREAM_FIELD),
        "the canonical payload carries no " + CiRunService.RELEASE_REQUEST_DOWNSTREAM_FIELD);
    JsonNode downstream = payload.get(CiRunService.RELEASE_REQUEST_DOWNSTREAM_FIELD);
    assertTrue(downstream.isArray(), "the closure travels as a JSON array of repository names");
    assertEquals(2, downstream.size());
    // Order is information: qits-projects sends the closure nearest-first, and while the ordering
    // does not depend on it today, an array that arrived reversed would be a different statement.
    assertEquals("qits-ci-frontend", downstream.get(0).asText());
    assertEquals("qits-ci-service", downstream.get(1).asText());
  }

  /**
   * The closure's own compatibility arm, and the one this rollout is actually sitting in: a
   * qits-projects that cannot reach qits-maintenance — or has not shipped the enrichment at all —
   * publishes a null, which NON_NULL turns into no key. An empty array is a <em>different</em>
   * statement ("asked, this repository is a leaf") and travels as real information; qits-ci orders
   * both identically, which is why nothing here has to tell them apart.
   */
  @Test
  public void aReFoldThatCouldNotAskCarriesNoClosureKeyAtAll() throws Exception {
    JsonNode unasked =
        MAPPER.readTree(canonicalPayload("r-1", "rr-42", "c".repeat(40), "HIGHER", null));
    JsonNode leaf =
        MAPPER.readTree(canonicalPayload("r-1", "rr-42", "c".repeat(40), "HIGHER", List.of()));

    assertFalse(
        unasked.has(CiRunService.RELEASE_REQUEST_DOWNSTREAM_FIELD),
        "NON_NULL: 'could not ask' is an absent key, indistinguishable from a pre-change event");
    assertTrue(
        leaf.has(CiRunService.RELEASE_REQUEST_DOWNSTREAM_FIELD),
        "'asked, it is a leaf' is an empty array and travels");
    assertEquals(0, leaf.get(CiRunService.RELEASE_REQUEST_DOWNSTREAM_FIELD).size());
  }

  /**
   * And the compatibility arm, which is the ordinary case: the field is additive, so a re-fold
   * published before it existed — or by a qits-projects that has not been released yet, which is the
   * live state of the rollout — carries no {@code priority} KEY at all rather than a null. Every
   * field a QA pipeline depends on is byte-identical either way, which is the whole reason this
   * repository can be released independently of the one that publishes the event.
   */
  @Test
  public void aReFoldStatingNeitherNewFieldCarriesNoSuchKeyAndIsOtherwiseIdentical()
      throws Exception {
    JsonNode enriched =
        MAPPER.readTree(
            canonicalPayload("r-1", "rr-42", "c".repeat(40), "MEDIUM", List.of("qits-ci-frontend")));
    JsonNode without = MAPPER.readTree(canonicalPayload("r-1", "rr-42", "c".repeat(40)));

    assertFalse(
        without.has(CiRunService.PRIORITY_FIELD),
        "NON_NULL: an absent priority is an absent key, so a consumer cannot read 'none' as a value");
    assertFalse(
        without.has(CiRunService.RELEASE_REQUEST_DOWNSTREAM_FIELD),
        "and an absent closure is an absent key for the same reason");
    for (String field :
        List.of(
            BACKING_BRANCH_FIELD,
            MERGED_SHA_FIELD,
            CiRunService.RELEASE_REQUEST_ID_FIELD,
            "repoId",
            "repoName",
            "changedAt")) {
      assertEquals(
          enriched.get(field),
          without.get(field),
          field + " moved with the new components, so they were not additive after all");
    }
  }

  /**
   * The two the payload must NOT carry. {@code eventId} and {@code occurredAt} are {@code
   * QitsEvent}'s own accessors and the canonical mix-in hides every one of them by signature —
   * identity and time travel in the envelope, which is where the trigger engine reads them from.
   * Note {@code changedAt} IS in the payload and is also the envelope's {@code occurredAt}: the
   * record's own component keeps its name.
   */
  @Test
  public void theEnvelopesOwnFieldsAreNotInThePayload() throws Exception {
    JsonNode payload = MAPPER.readTree(canonicalPayload("r-1", "rr-42", "c".repeat(40)));

    assertFalse(payload.has("eventId"), "identity travels in the envelope, never in the payload");
    assertFalse(payload.has("occurredAt"), "and so does the timestamp");
    assertTrue(payload.has("changedAt"), "the record's own timestamp component stays a field");
  }
}
