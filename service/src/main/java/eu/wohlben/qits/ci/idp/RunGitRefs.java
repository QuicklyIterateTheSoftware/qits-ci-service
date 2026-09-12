package eu.wohlben.qits.ci.idp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The Git refs a CI run may push, read from the event that triggered it. The run's commission
 * states them as {@code gitRefs}, and qits-idp stamps them into every token of that client as
 * {@code git_refs} (contract C6 in the superproject's {@code principal-bound-git-refs-plan.md}).
 *
 * <p>Four answers:
 *
 * <ul>
 *   <li><b>{@code MaintenanceBump}</b>: the one branch the payload names in {@code branch}. For a
 *       group bump that is {@code maintenance/<group>}; for a targeted bump it is the source branch
 *       the caller asked to bump. It is the only ref the bump pipeline
 *       ({@code .config/qits/ci-platform-event-maintenance-bump.yml} in the wrapper) pushes. A
 *       payload with no usable branch gives an empty list: the pipeline refuses such a payload
 *       before it pushes anything.
 *   <li><b>{@code ReleaseRequestChanged} and {@code SCMRelease}</b>: an empty list, "may push
 *       nothing". Inventory of 2026-09-12: 46 and 31 recipes across the estate, and none of them
 *       pushes — they only fetch release tags.
 *   <li><b>{@code SoftwareRelease}</b>: an empty list too. Inventory of 2026-09-12: no recipe on
 *       any {@code origin/main} of the estate selects it. The hop files
 *       ({@code ci-event-upstream-*.yml}) that force-pushed {@code maintenance/<payload.repository>}
 *       were deleted on 2026-09-02/03. qits-platform-maintenance follows those releases now, through
 *       a {@code MaintenanceBump} run. If a hop comes back, scope it here in the same change.
 *   <li><b>Any other event, or none</b>: empty {@code Optional}. The commission states no scope,
 *       which is the behaviour before this class existed. Nobody has checked what such a run
 *       pushes, so an empty list could break it.
 * </ul>
 *
 * <p>Pure: no I/O, no clock, no config. The mapper is passed in because a static one would be built
 * into the native image heap.
 */
public final class RunGitRefs {

  /** The event qits-platform-maintenance sends to apply a bump. */
  public static final String MAINTENANCE_BUMP = "MaintenanceBump";

  /** The payload field that names the branch the bump pipeline pushes. */
  static final String BRANCH_FIELD = "branch";

  /**
   * Events whose recipes are known to push nothing. Add an event here only after checking every
   * recipe that declares it: an empty list refuses every push the run makes.
   */
  static final Set<String> PUSH_NOTHING =
      Set.of("ReleaseRequestChanged", "SCMRelease", "SoftwareRelease");

  /** The two run-scoped variables every event-triggered step already carries. */
  static final String EVENT_NAME_VARIABLE = "QITS_EVENT_NAME";

  static final String EVENT_PAYLOAD_VARIABLE = "QITS_EVENT_PAYLOAD";

  static final String HEADS = "refs/heads/";

  /** qits-idp refuses a longer entry. */
  static final int MAX_REF_LENGTH = 255;

  /**
   * A plain branch name: the same rule qits-platform-maintenance's {@code BumpPayload} applies, and
   * stricter than the pipeline's own check. No {@code *}, so a stated ref is always exact.
   */
  private static final Pattern BRANCH = Pattern.compile("[0-9A-Za-z._-]+(?:/[0-9A-Za-z._-]+)*");

  private RunGitRefs() {}

  /** The scope for a run, read from its run-scoped environment ({@code QITS_EVENT_*}). */
  public static Optional<List<String>> fromRunEnv(Map<String, String> runEnv, ObjectMapper json) {
    if (runEnv == null) {
      return Optional.empty();
    }
    return of(runEnv.get(EVENT_NAME_VARIABLE), runEnv.get(EVENT_PAYLOAD_VARIABLE), json);
  }

  /**
   * The scope for a run triggered by this event. An empty {@code Optional} means "state nothing"; an
   * empty list means "may push nothing".
   */
  public static Optional<List<String>> of(String eventName, String payload, ObjectMapper json) {
    if (MAINTENANCE_BUMP.equals(eventName)) {
      String branch = branchOf(payload, json);
      return Optional.of(branch == null ? List.of() : List.of(HEADS + branch));
    }
    if (eventName != null && PUSH_NOTHING.contains(eventName)) {
      return Optional.of(List.of());
    }
    return Optional.empty();
  }

  /** The payload's branch, or null when it is missing or not a plain branch name. */
  private static String branchOf(String payload, ObjectMapper json) {
    if (payload == null || payload.isBlank()) {
      return null;
    }
    JsonNode root;
    try {
      root = json.readTree(payload);
    } catch (Exception notJson) {
      return null;
    }
    JsonNode branch = root == null ? null : root.get(BRANCH_FIELD);
    if (branch == null || !branch.isTextual()) {
      return null;
    }
    String name = branch.asText();
    boolean plain =
        BRANCH.matcher(name).matches()
            && !name.startsWith("-")
            && !name.contains("..")
            && HEADS.length() + name.length() <= MAX_REF_LENGTH;
    return plain ? name : null;
  }
}
