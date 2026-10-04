package eu.wohlben.qits.ci.bus;

import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.githost.events.SCMPublishCommit;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A push, as it arrives: one {@link EventFrame} carrying an {@link SCMPublishCommit} payload.
 *
 * <p>This is what every suite here used to build as a JSON body for {@code POST
 * /ci/api/events/post-receive}. The endpoint is gone, so a test drives {@code
 * ScmPublishCommitListener} instead — and the frame is built through {@link CanonicalJson} from a
 * <b>real</b> {@code SCMPublishCommit} rather than from a hand-written string, so what a test hands
 * the listener is byte for byte what qits-githost publishes. {@link #commit} assembles that instance
 * by decoding a map of wire keys rather than by calling the record's own constructor, precisely so
 * that a boolean flag the publisher has retired, as of this writing, costs this fixture nothing: the
 * key is simply not in the map, and Jackson binds the missing primitive to its default. A field
 * truly renamed (not merely dropped) still shows up as a mismatch at this seam, since the decode
 * fails loudly when the resulting JSON does not round-trip through the real type.
 *
 * <p>Shared across packages ({@code api} drives it too) rather than copied per test class, which is
 * the opposite of what this repo does with {@code FakeCiStepRunner} — those are duplicated because
 * the two MODULES do not share a test classpath, and everything here is one module.
 */
public final class ScmPushFrames {

  /** The all-zero sha git reports as the old id of a newly created branch. */
  public static final String ZERO_SHA = "0".repeat(40);

  private ScmPushFrames() {}

  /** An ordinary push: the branch moved, and CI is meant to build it. */
  public static EventFrame push(String repoId, String branch, String oldSha, String sha) {
    return frame(commit(repoId, null, null, branch, oldSha, sha));
  }

  /**
   * A push that arrived on the <b>name-addressed</b> route: the same payload, with the {@code
   * projectId}/{@code repoName} the git host fills in from the address set on the record, so the
   * canonical payload carries them exactly as a real name-addressed push does.
   */
  public static EventFrame named(
      String repoId, String projectId, String repoName, String branch, String oldSha, String sha) {
    return frame(commit(repoId, projectId, repoName, branch, oldSha, sha));
  }

  /**
   * The record itself, with the head-commit metadata the HTTP event never carried filled in as a
   * real push would fill it. None of it reaches a run row today; it is here because a payload that
   * omitted it would not be the payload under test. {@code projectId}/{@code repoName} are null for
   * an id-addressed push and set for a name-addressed one.
   *
   * <p>Built by decoding wire JSON that simply omits the one boolean flag the git host retired —
   * through the same lenient mapper a real consumer reads a payload with
   * ({@link CanonicalJson#payloadTo}, which disables {@code FAIL_ON_UNKNOWN_PROPERTIES} and so, by
   * the same token, tolerates a key the current {@code SCMPublishCommit} does not have yet). That
   * keeps this fixture compiling against both the still-pinned githost-events jar, which still
   * declares that component, and whatever version drops it: a missing primitive {@code boolean}
   * record component binds to {@code false} rather than failing the read.
   */
  public static SCMPublishCommit commit(
      String repoId,
      String projectId,
      String repoName,
      String branch,
      String oldSha,
      String sha) {
    Instant receivedAt = Instant.parse("2026-08-10T09:00:00Z");
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("repoId", repoId);
    fields.put("projectId", projectId);
    fields.put("repoName", repoName);
    fields.put("branch", branch);
    fields.put("oldSha", oldSha);
    fields.put("sha", sha);
    fields.put("parents", ZERO_SHA.equals(oldSha) ? List.of() : List.of(oldSha));
    fields.put("authorName", "A Pusher");
    fields.put("authorEmail", "pusher@example.invalid");
    fields.put("authoredAt", receivedAt);
    fields.put("committedAt", receivedAt);
    fields.put("message", "a commit");
    fields.put("receivedAt", receivedAt);
    return CanonicalJson.payloadTo(CanonicalJson.canonicalize(fields), SCMPublishCommit.class);
  }

  /** The envelope a publisher would have written, wrapped as the frame a consumer is handed. */
  public static EventFrame frame(SCMPublishCommit commit) {
    return new EventFrame(
        UUID.randomUUID().toString(),
        SCMPublishCommit.class.getSimpleName(),
        commit.occurredAt(),
        CanonicalJson.payload(commit),
        null,
        null, null);
  }
}
