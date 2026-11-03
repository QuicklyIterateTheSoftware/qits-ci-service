package eu.wohlben.qits.ci.targethost;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.ci.control.CiAutomationComposer;
import eu.wohlben.qits.ci.control.CiRepoRef;
import eu.wohlben.qits.ci.control.CiSlot;
import eu.wohlben.qits.ci.idp.IdpCommissioner;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * <b>What one CI lease is</b> (epic qits-1481, "CI in a slot"): the {@code LeaseSpec{image, env,
 * user, limits}} {@link CiLeases} asks qits-system for when a run is accepted. Its container runs
 * every step of the run, so the spec carries the run's environment and nothing about any one step —
 * the step index, and whatever else differs per step, travels in each {@code RunStep}'s own {@code
 * env} instead ({@link #stepEnv}).
 *
 * <p><b>Pure</b>: settings, addresses, the run, its token and its pinned image in, a spec out, no
 * I/O. It was {@code runnerhost/StepWorkloadSpecs}, the per-step container's spec for a CI runner's
 * {@code Launch}; what a slot's policy owns now is not in it at all — mounts, networks, capabilities,
 * {@code --init}, the caches and {@code $BUILDKIT_HOST} are the target's, fixed per slot type, and
 * qits-system refuses a spec env naming {@code BUILDKIT_HOST}, {@code HOME} or {@code
 * CLAUDE_CONFIG_DIR} ({@link #POLICY_ENV}).
 *
 * <p><b>What a spec never carries</b> (qits-515, still): a docker network, an extra host, a qits-net
 * alias, a commissioned client pair, a {@code QITS_GIT_AUTH_*} variable, a per-container daemon
 * secret — or a docker socket, since there is none in a slot.
 */
public final class CiSlotSpecs {

  private CiSlotSpecs() {}

  /** The env keys a slot's policy owns; a spec carrying one is refused (qits-system's list). */
  static final Set<String> POLICY_ENV = Set.of("BUILDKIT_HOST", "HOME", "CLAUDE_CONFIG_DIR");

  /** The user half of a lease's registry login: the edge reads the password and introspects it. */
  static final String TOKEN_LOGIN = "token";

  /** The deployment facts a spec is composed from that are not addresses. */
  public record Settings(
      String artifactsImageRepository,
      String artifactsCliPackage,
      String artifactsCliVersion,
      String memory,
      String memorySwap,
      long pidsLimit,
      String cpus,
      Integer oomScoreAdj) {}

  /**
   * The run a lease is for. {@code daemonId} is the run's one launch id, {@code daemonBinaryPath} the
   * run-pinned binary's path ({@code CiSlotSettings.resolveBinaryUrl}), {@code image} the run's
   * pinned ci-slot reference, and {@code env} the run-scoped extras ({@code QITS_EVENT_*} and
   * {@code QITS_VERSION}) — never anything a repository authored.
   */
  public record LeaseRun(
      String runId,
      CiRepoRef repo,
      String branch,
      String sha,
      String daemonId,
      String daemonBinaryPath,
      String image,
      Map<String, String> env) {}

  /**
   * The lease's spec. {@code token} is the run's {@code ci-run} token, and it is required: a lease
   * with no token can reach nothing, so the caller asks for none rather than composing it.
   */
  public static SystemLeases.LeaseSpec compose(
      Settings settings,
      CiSlotAddresses.Plane plane,
      LeaseRun run,
      IdpCommissioner.CommissionedToken token) {
    Objects.requireNonNull(token, "a lease is never composed without its run's token");
    // The run-scoped keys, from the one place a Continue's env is made too (epic qits-732), so the
    // two can never drift; laid in at their own positions below. QITS_CI_SHA is the worktree's
    // commit, which for a release-request automation is its fold rather than the recorded main
    // head (qits-1836, worktreeSha).
    Map<String, String> runScoped =
        runEnv(plane, run.runId(), run.branch(), worktreeSha(run.sha(), run.env()), token);
    Map<String, String> env = new LinkedHashMap<>();
    // The daemon's contract, as environment: it needs all of it before a socket exists.
    env.put("QITS_CI_DAEMON_ID", value(run.daemonId()));
    env.put("QITS_CI_DAEMON_URL", plane.daemonUrl());
    env.put("QITS_CI_DAEMON_BINARY_URL", plane.daemonBinaryUrl(run.daemonBinaryPath()));
    // The version that path ends with: BOOTSTRAP keys the target's daemon cache by it (qits-1809).
    env.put("QITS_CI_DAEMON_VERSION", daemonVersionOf(run.daemonBinaryPath()));
    env.put("QITS_CI_REPOSITORY_URL", cloneUrl(plane.gitBaseUrl(), run.repo()));
    env.put("QITS_CI_BRANCH", runScoped.get("QITS_CI_BRANCH"));
    env.put("QITS_CI_SHA", runScoped.get("QITS_CI_SHA"));
    // Which run (qits-754; required by the daemon since capability 2: its worktree is
    // /work/<runId>). The step index is each step's own and arrives in its RunStep (stepEnv).
    env.put("QITS_CI_RUN_ID", runScoped.get("QITS_CI_RUN_ID"));
    // The repository, in both coordinate systems — the public pair names the daemon's warm mirror,
    // /cache/git/<projectId>/<repoName>.git. Empty, never absent, on an id-addressed run.
    env.put("QITS_CI_REPO_ID", value(run.repo().repoId()));
    env.put("QITS_CI_PROJECT_ID", value(run.repo().projectId()));
    env.put("QITS_CI_REPO_NAME", value(run.repo().name()));
    env.put("CI", "true");
    env.put("QITS_CI", "true");
    // The address input every recipe derives from (qits-731), and the two maven urls every
    // repository's committed settings read (qits-896).
    env.put("QITS_DOMAIN", plane.domain());
    env.put("QITS_MAVEN_REPOSITORY_URL", plane.mavenRepositoryUrl());
    env.put("QITS_MAVEN_CENTRAL_URL", plane.mavenCentralUrl());
    env.put("QITS_IMAGE_REPOSITORY", value(settings.artifactsImageRepository()));
    env.put("QITS_ARTIFACTS_CLI_PACKAGE", value(settings.artifactsCliPackage()).trim());
    env.put("QITS_ARTIFACTS_CLI_VERSION", value(settings.artifactsCliVersion()));
    env.put("QITS_WORKSPACES_URL", plane.workspacesUrl());
    // THE RUN'S CREDENTIAL, and the only one: its ci-run token, minted when the run was accepted and
    // deleted when it closes (CiLeases). BOOTSTRAP turns it into a git helper, a publish command,
    // maven settings and an npmrc; QITS_MAVEN_AUTH_* are the pair a repository's own settings read.
    env.put("QITS_TOKEN", runScoped.get("QITS_TOKEN"));
    env.put("QITS_TOKEN_SUBJECT", runScoped.get("QITS_TOKEN_SUBJECT"));
    env.put("QITS_MAVEN_AUTH_USR", runScoped.get("QITS_MAVEN_AUTH_USR"));
    env.put("QITS_MAVEN_AUTH_PSW", runScoped.get("QITS_MAVEN_AUTH_PSW"));
    env.put("GIT_CONFIG_GLOBAL", "/tmp/qits-gitconfig");
    env.put("QITS_PUBLISH_TOKEN_COMMAND", CiSlotSettings.PUBLISH_TOKEN_COMMAND);
    // THE BUILD-MODE ENVIRONMENT, for every lease: every CI lease is given the target's buildkitd
    // ($BUILDKIT_HOST, the slot's policy), so any step of the run may build, and a build logs in to
    // the registry and the mirror with this run's token. One entry per public host, `token:<tok>`,
    // the Basic form the edge's docker realm introspects; BOOTSTRAP writes the document under
    // DOCKER_CONFIG, outside the worktree. It used to be given only to a `build:`/`docker:` step's
    // container; a lease is the whole run's, and its token is already in this environment raw.
    env.put("DOCKER_CONFIG", CiSlotSettings.REGISTRY_AUTH_DIR);
    env.put("QITS_CI_REGISTRY_AUTH_CONFIG", runScoped.get("QITS_CI_REGISTRY_AUTH_CONFIG"));
    // Run-scoped extras, LAST and in sorted key order: the QITS_EVENT_* of the trigger and
    // QITS_VERSION. None of them is repository-authored, and none may shadow a policy key.
    for (Map.Entry<String, String> extra : new TreeMap<>(run.env()).entrySet()) {
      if (!POLICY_ENV.contains(extra.getKey())) {
        env.put(extra.getKey(), value(extra.getValue()));
      }
    }
    // The slot's command: the ci-slot image's entrypoint runs whatever this variable carries
    // (`/bin/sh -c "$QITS_CI_BOOTSTRAP"`), since a lease spec has no entrypoint field. Put last,
    // so no run-scoped extra can shadow it.
    env.put(CiSlotSettings.BOOTSTRAP_ENV, CiSlotSettings.BOOTSTRAP);
    return new SystemLeases.LeaseSpec(
        plane.slotImage(run.image()),
        env,
        CiSlot.USER,
        new SystemLeases.Limits(
            settings.memory(),
            settings.memorySwap(),
            settings.pidsLimit(),
            settings.cpus(),
            settings.oomScoreAdj()));
  }

  /** The keys of {@link #runEnv}, in the order it writes them. */
  public static final List<String> RUN_SCOPED_KEYS =
      List.of(
          "QITS_CI_BRANCH",
          "QITS_CI_SHA",
          "QITS_CI_RUN_ID",
          "QITS_TOKEN",
          "QITS_TOKEN_SUBJECT",
          "QITS_MAVEN_AUTH_USR",
          "QITS_MAVEN_AUTH_PSW",
          "QITS_CI_REGISTRY_AUTH_CONFIG");

  /**
   * The run-scoped part of a lease's environment (epic qits-732): exactly the keys {@link #compose}
   * writes that change from one run to the next on the same container — which run, which ref and
   * commit, and the run's token in every form it is spent in. A {@code Continue} carries this map to
   * a held slot's daemon, which lays it over the lease's environment for the next run's steps;
   * {@link #compose} reads its values from here, so the two are one definition.
   */
  public static Map<String, String> runEnv(
      CiSlotAddresses.Plane plane,
      String runId,
      String branch,
      String sha,
      IdpCommissioner.CommissionedToken token) {
    Objects.requireNonNull(token, "a run's environment is never made without its token");
    Map<String, String> env = new LinkedHashMap<>();
    env.put("QITS_CI_BRANCH", value(branch));
    env.put("QITS_CI_SHA", value(sha));
    env.put("QITS_CI_RUN_ID", value(runId));
    env.put("QITS_TOKEN", value(token.token()));
    env.put("QITS_TOKEN_SUBJECT", value(token.subject()));
    env.put("QITS_MAVEN_AUTH_USR", value(token.subject()));
    env.put("QITS_MAVEN_AUTH_PSW", value(token.token()));
    env.put(
        "QITS_CI_REGISTRY_AUTH_CONFIG",
        registryAuthConfig(TOKEN_LOGIN, token.token(), plane.authHosts()));
    return env;
  }

  /**
   * What a {@code Continue} carries (qits-732): {@link #runEnv} and then the run's own extras — the
   * {@code QITS_EVENT_*} of its trigger and {@code QITS_VERSION} — in the order and under the same
   * policy-key guard {@link #compose} writes them. Leaving the extras out handed a release run the
   * held QA run's event, with a blank {@code QITS_VERSION}, and every release step failed on it.
   * Its {@code QITS_CI_SHA} is {@link #worktreeSha} of {@code sha} and those extras, exactly as
   * {@link #compose}'s is (qits-1836).
   */
  public static Map<String, String> continueEnv(
      CiSlotAddresses.Plane plane,
      String runId,
      String branch,
      String sha,
      IdpCommissioner.CommissionedToken token,
      Map<String, String> extras) {
    Map<String, String> env =
        new LinkedHashMap<>(runEnv(plane, runId, branch, worktreeSha(sha, extras), token));
    for (Map.Entry<String, String> extra : new TreeMap<>(extras).entrySet()) {
      if (!POLICY_ENV.contains(extra.getKey())) {
        env.put(extra.getKey(), value(extra.getValue()));
      }
    }
    return env;
  }

  /** What a fold sha may spell: sha-1 or sha-256, lowercase — the automation prelude's own rule. */
  private static final Pattern FOLD_SHA = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");

  /**
   * The commit a lease's worktree is made at — its {@code $QITS_CI_SHA} — for a run recorded at
   * {@code recordedSha} with the run-scoped extras {@code runEnv} (qits-1836). For every run it is
   * the recorded sha, except a run of a composed {@code ReleaseRequestAutomation} trigger ({@link
   * CiAutomationComposer#EVENT}): that run is deliberately <b>recorded</b> at the target's {@code
   * main} head, so its {@code BuildSuccessful}/{@code BuildFailed} is never read as the fold's own
   * verdict, but its worktree is prepared at the payload's {@code foldSha}. The daemon makes the
   * worktree at {@code $QITS_CI_SHA} and runs {@code git submodule init/update} from that commit's
   * {@code .gitmodules}, before the composed prelude ever fetches and checks out the fold: at
   * {@code main}'s head, a fold that renames a submodule failed every run {@code INIT_FAILED}
   * cloning a repository the git host no longer has (qits-qits, the old {@code
   * qits-platform-access-cli}). At the fold, the submodules are materialised from the fold's {@code
   * .gitmodules} at the fold's gitlinks, and the prelude's checkout lands on the same commit.
   *
   * <p>Only the worktree moves: the run's row, its {@code main@<head>} label, every build event's
   * {@code commitSha} and the {@code SHA_GONE} probe all stay at the recorded sha — a fold gone from
   * the mirror is one failed step, since {@code main}'s head is still held. A payload without a
   * {@code foldSha} the prelude would accept (40 or 64 lowercase hex) keeps the recorded sha, and
   * the prelude then refuses the payload itself. The mapper is local rather than static: a static
   * one would be built into the native image heap, and this is read once per automation run.
   */
  public static String worktreeSha(String recordedSha, Map<String, String> runEnv) {
    if (runEnv == null || !CiAutomationComposer.EVENT.equals(runEnv.get("QITS_EVENT_NAME"))) {
      return recordedSha;
    }
    String payload = runEnv.get("QITS_EVENT_PAYLOAD");
    if (payload == null || payload.isBlank()) {
      return recordedSha;
    }
    JsonNode fold;
    try {
      JsonNode root = new ObjectMapper().readTree(payload);
      fold = root == null ? null : root.get("foldSha");
    } catch (JsonProcessingException notJson) {
      return recordedSha;
    }
    if (fold == null || !fold.isTextual() || !FOLD_SHA.matcher(fold.asText()).matches()) {
      return recordedSha;
    }
    return fold.asText();
  }

  /**
   * The part of a step's environment that is the step's own, sent in its {@code RunStep} and laid
   * over the lease's for that step's child only: its index (qits-754's {@code QITS_CI_STEP_INDEX},
   * which the QA report hook reads to say which step its reports belong to) and, since qits-1807,
   * {@code QITS_CI_BUILDER_WAIT_MAX_SECONDS} — {@code qits.ci.builder-wait-max-seconds}, the most
   * qits-ci-daemon may pause this step's own deadline while a {@code buildctl build} of it waits at
   * the target's shared builder gate rather than building. This is the one place every lease step's
   * {@code RunStep} env is assembled — the run, the reattach and the inherited-step paths in {@link
   * LeaseStepRunner} all call it — and it is never a repository's to declare, so there is nothing for
   * one to shadow.
   */
  public static Map<String, String> stepEnv(int stepIndex, long builderWaitMaxSeconds) {
    Map<String, String> env = new LinkedHashMap<>();
    env.put("QITS_CI_STEP_INDEX", String.valueOf(stepIndex));
    env.put("QITS_CI_BUILDER_WAIT_MAX_SECONDS", String.valueOf(builderWaitMaxSeconds));
    return env;
  }

  /**
   * The smart-HTTP url of a repository: {@code <base>/git/<projectId>/<repoName>} when the run
   * carries the public coordinate, the id-addressed {@code <base>/git/<repoId>} when it does not.
   * The daemon's {@code $QITS_CI_REPOSITORY_URL}, which its warm mirror is cloned and fetched from.
   */
  static String cloneUrl(String gitBaseUrl, CiRepoRef repo) {
    String base = gitBaseUrl.replaceAll("/+$", "") + "/git/";
    return repo.named() ? base + repo.projectId() + "/" + repo.name() : base + repo.repoId();
  }

  /**
   * The docker {@code config.json} a lease's registry login is carried in: one entry per host, each
   * {@code user:secret} base64-encoded (docker's own encoding, not encryption). Hand-written JSON —
   * base64 needs no escaping, and the hosts are escaped anyway.
   */
  static String registryAuthConfig(String user, String secret, List<String> hosts) {
    String auth =
        Base64.getEncoder().encodeToString((user + ":" + secret).getBytes(StandardCharsets.UTF_8));
    StringBuilder document = new StringBuilder("{\"auths\":{");
    boolean first = true;
    for (String host : hosts) {
      if (!first) {
        document.append(',');
      }
      first = false;
      document
          .append('"')
          .append(host.replace("\\", "\\\\").replace("\"", "\\\""))
          .append("\":{\"auth\":\"")
          .append(auth)
          .append("\"}");
    }
    return document.append("}}").toString();
  }

  /**
   * The pinned daemon version a binary path names: its last segment ({@code
   * /artifacts/daemons/qits-ci-daemon/<version>}, {@code CiSlotSettings.resolveBinaryUrl}), so the
   * cache key and the download can never disagree. Empty when there is no path.
   */
  static String daemonVersionOf(String daemonBinaryPath) {
    String path = value(daemonBinaryPath).replaceAll("/+$", "");
    return path.substring(path.lastIndexOf('/') + 1);
  }

  private static String value(String text) {
    return text == null ? "" : text;
  }
}
