package eu.wohlben.qits.ci.targethost;

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
    Map<String, String> env = new LinkedHashMap<>();
    // The daemon's contract, as environment: it needs all of it before a socket exists.
    env.put("QITS_CI_DAEMON_ID", value(run.daemonId()));
    env.put("QITS_CI_DAEMON_URL", plane.daemonUrl());
    env.put("QITS_CI_DAEMON_BINARY_URL", plane.daemonBinaryUrl(run.daemonBinaryPath()));
    env.put("QITS_CI_REPOSITORY_URL", cloneUrl(plane.gitBaseUrl(), run.repo()));
    env.put("QITS_CI_BRANCH", value(run.branch()));
    env.put("QITS_CI_SHA", value(run.sha()));
    // Which run (qits-754; required by the daemon since capability 2: its worktree is
    // /work/<runId>). The step index is each step's own and arrives in its RunStep (stepEnv).
    env.put("QITS_CI_RUN_ID", value(run.runId()));
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
    env.put("QITS_TOKEN", value(token.token()));
    env.put("QITS_TOKEN_SUBJECT", value(token.subject()));
    env.put("QITS_MAVEN_AUTH_USR", value(token.subject()));
    env.put("QITS_MAVEN_AUTH_PSW", value(token.token()));
    env.put("GIT_CONFIG_GLOBAL", "/tmp/qits-gitconfig");
    env.put("QITS_PUBLISH_TOKEN_COMMAND", CiSlotSettings.PUBLISH_TOKEN_COMMAND);
    // THE BUILD-MODE ENVIRONMENT, for every lease: every CI lease is given the target's buildkitd
    // ($BUILDKIT_HOST, the slot's policy), so any step of the run may build, and a build logs in to
    // the registry and the mirror with this run's token. One entry per public host, `token:<tok>`,
    // the Basic form the edge's docker realm introspects; BOOTSTRAP writes the document under
    // DOCKER_CONFIG, outside the worktree. It used to be given only to a `build:`/`docker:` step's
    // container; a lease is the whole run's, and its token is already in this environment raw.
    env.put("DOCKER_CONFIG", CiSlotSettings.REGISTRY_AUTH_DIR);
    env.put(
        "QITS_CI_REGISTRY_AUTH_CONFIG",
        registryAuthConfig(TOKEN_LOGIN, token.token(), plane.authHosts()));
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

  /**
   * The part of a step's environment that is the step's own, sent in its {@code RunStep} and laid
   * over the lease's for that step's child only: its index (qits-754's {@code QITS_CI_STEP_INDEX},
   * which the QA report hook reads to say which step its reports belong to).
   */
  public static Map<String, String> stepEnv(int stepIndex) {
    return Map.of("QITS_CI_STEP_INDEX", String.valueOf(stepIndex));
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

  private static String value(String text) {
    return text == null ? "" : text;
  }
}
