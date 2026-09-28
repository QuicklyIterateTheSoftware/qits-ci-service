package eu.wohlben.qits.ci.daemonhost;

import eu.wohlben.qits.ci.control.CiRepoRef;
import eu.wohlben.qits.ci.idp.IdpCommissioner;
import eu.wohlben.qits.ci.idp.RunCommissions;
import eu.wohlben.qits.containers.client.ContainersWire.Security;
import eu.wohlben.qits.containers.client.ContainersWire.Spec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * <b>What one step container is</b>: the whole workload spec, composed once, for whichever
 * transport starts it. {@link CiDaemonLauncher} hands it to qits-containers; {@code
 * RunnerStepRunner} maps it onto the runner protocol's {@code WorkloadSpec} and hands it to a
 * runner. Two compositions would be two chances for a step on a runner to be a different step —
 * a missing variable, a sandbox flag dropped — and the difference would surface as a build that
 * passes in one place and fails in the other with nothing in either spec to say why.
 *
 * <p><b>Pure</b>: settings, the address plane, the launch and the run's commission in, a spec out,
 * no I/O. The commission is looked up by each caller (it is the one input that can fail, and each
 * transport records that failure its own way), everything configured that is not an address arrives
 * as {@link Settings}, read off the launcher at the moment of asking, and every address arrives as a
 * {@link StepAddressPlane} — the launcher's internal one for a local step and an INTERNAL runner, its
 * edge rendering for an EDGE runner. The reasoning for every line is where it always was,
 * on {@link CiDaemonLauncher#buildWorkloadSpec}; the lines themselves moved here unchanged, and
 * {@code CiDaemonLauncherTest} and {@code RunCommissioningTest} assert the result literally.
 */
public final class StepWorkloadSpecs {

  private StepWorkloadSpecs() {}

  /**
   * The deployment facts a spec is composed from that are NOT addresses — every one of them a config
   * value the launcher holds, or a value it derives from them ({@code artifactsCliVersion}), resolved
   * before it gets here. Every address is the {@link StepAddressPlane}'s, which is what lets one
   * step be composed for either side of the edge from the same settings.
   */
  public record Settings(
      String artifactsImageRepository,
      boolean mavenCentralMirrorEnabled,
      String artifactsCliPackage,
      String artifactsCliVersion,
      boolean buildkitEnabled,
      String memoryLimit,
      long pidsLimit,
      String cpus,
      Integer oomScoreAdj) {}

  /**
   * The step container's spec, on {@code plane}. {@code credential} is this run's commissioned
   * credential — its client on qits-net, its {@code ci-run} token through the edge — or null on a
   * deployment that commissions nothing; see {@link CiDaemonLauncher#buildWorkloadSpec}.
   */
  public static Spec compose(
      Settings settings,
      StepAddressPlane plane,
      CiDaemonLauncher.LaunchSpec spec,
      RunCommissions.Credential credential) {
    IdpCommissioner.Commission commission = credential == null ? null : credential.client();
    IdpCommissioner.CommissionedToken token = credential == null ? null : credential.token();
    Map<String, String> env = new LinkedHashMap<>();
    // The contract, as environment. The daemon needs all of it before a socket exists, which is why
    // none of it is a message.
    env.put("QITS_CI_DAEMON_ID", value(spec.daemonId()));
    env.put("QITS_CI_DAEMON_SECRET", value(spec.secret()));
    env.put("QITS_CI_DAEMON_URL", value(plane.daemonUrl()));
    env.put("QITS_CI_DAEMON_BINARY_URL", value(plane.daemonBinaryUrl(spec.daemonBinaryUrl())));
    env.put("QITS_CI_REPOSITORY_URL", value(cloneUrl(plane.gitBaseUrl(), spec.repo())));
    env.put("QITS_CI_BRANCH", value(spec.branch()));
    env.put("QITS_CI_SHA", value(spec.sha()));
    // The repository, in both coordinate systems. QITS_CI_REPO_ID is the storage id the event
    // announced and keeps its meaning exactly, while the pair beside it is the public address —
    // which is what every release call in the estate now spells, the storage id staying below the
    // projects↔githost seam.
    // Empty, never absent, when the announcing push was id-addressed: a step reading an unset
    // variable and one reading an empty one behave the same, and one shape is one thing to document.
    env.put("QITS_CI_REPO_ID", value(spec.repo().repoId()));
    env.put("QITS_CI_PROJECT_ID", value(spec.repo().projectId()));
    env.put("QITS_CI_REPO_NAME", value(spec.repo().name()));
    // For the step script rather than the daemon: the de-facto convention tooling checks for
    // non-interactive mode, and one that says which CI this is.
    env.put("CI", "true");
    env.put("QITS_CI", "true");
    // Also for the script: where a published image goes. Every container gets them, because "which
    // registry" must never be a literal in a repository's pipeline. Together with $QITS_CI_SHA above
    // they are the whole of the tag convention qits-cd pulls by,
    // <registry>/<repository>/<application>:<sha>.
    env.put("QITS_REGISTRY", value(plane.registryHost()));
    env.put("QITS_IMAGE_REPOSITORY", value(settings.artifactsImageRepository()));
    // And where npm packages come from and go to. Unlike the two above, these are dialled by this
    // container, on this network — a publish here is an ordinary HTTP step needing no socket.
    env.put("QITS_NPM_REGISTRY_URL", value(plane.npmHostedUrl()));
    env.put("QITS_NPM_PROXY_URL", value(plane.npmProxyUrl()));
    env.put("QITS_MAVEN_REGISTRY_URL", value(plane.mavenRegistryUrl()));
    // Maven Central through qits-platform-mirror, both address planes — see the fields' javadoc.
    // Empty is the deliberate off state, so the ternary writes "" rather than skipping the keys:
    // a pipeline reads "${QITS_MAVEN_CENTRAL_MIRROR_URL:-}" either way and empty deactivates the
    // settings profile at every consumer.
    env.put("QITS_MAVEN_CENTRAL_MIRROR_URL",
        settings.mavenCentralMirrorEnabled() ? value(plane.mavenCentralMirrorBuildUrl()) : "");
    env.put("QITS_MAVEN_PROXY_URL",
        settings.mavenCentralMirrorEnabled() ? value(plane.mavenCentralMirrorStepUrl()) : "");
    env.put("QITS_DOCS_URL", value(plane.docsUrl()));
    // The store's own root, and the coordinate a composed release prelude downloads the qits CLI at.
    // The first two are EMPTY-never-absent, so a deployment that has switched the CLI off hands
    // every step one shape to read.
    //
    // THE VERSION IS A PIN AND IS NEVER EMPTY. It comes from this reactor's own dependency on
    // qits-platform-access-cli-binary, so which CLI every composed release step on the platform runs
    // is a line in a pom that a release request gated — not whatever was latest in the store at the
    // moment the step started. The constant cannot be blank (PlatformAccessCliBinary refuses that at
    // class-init), so the prelude's `:?` guard on it can only ever fire against a qits-ci that
    // predates the pin.
    env.put("QITS_ARTIFACTS_URL", value(plane.artifactsUrl()));
    env.put("QITS_ARTIFACTS_CLI_PACKAGE", value(settings.artifactsCliPackage()).trim());
    env.put("QITS_ARTIFACTS_CLI_VERSION", settings.artifactsCliVersion());
    // And where a step asks for its own repository to be released — same network, same reading of
    // "reachable from where" as the npm pair.
    env.put("QITS_WORKSPACES_URL", value(plane.workspacesUrl()));
    // Git never receives the commissioned client secret as an HTTP credential.  Its helper exchanges
    // that pair for a short-lived, audience-bound bearer when (and only when) Git asks for the
    // configured qits-githost authority.  The helper is installed by BOOTSTRAP below, outside the
    // checkout, so neither its configuration nor a token can enter a build context.
    // The run's own QITS_EVENT_* pair decides which Git refs that credential may push (RunGitRefs).
    if (commission != null) {
      env.put("QITS_COMMISSIONED_CLIENT_ID", value(commission.clientId()));
      env.put("QITS_COMMISSIONED_CLIENT_SECRET", value(commission.secret()));
      env.put("QITS_GIT_AUTH_TOKEN_URL", tokenUrl(plane.idpUrl()));
      env.put("QITS_GIT_AUTH_HOST", gitAuthority(plane.gitBaseUrl()));
      env.put("QITS_GIT_AUTH_AUDIENCE", CiDaemonLauncher.CONTAINER_GIT_AUDIENCE);
      env.put("GIT_CONFIG_GLOBAL", "/tmp/qits-gitconfig");
      // And the same credential in the form a PUBLISHING step needs: the script BOOTSTRAP writes,
      // named so a recipe can re-mint whenever it likes. $QITS_PUBLISH_TOKEN itself is exported by
      // that same bootstrap rather than sent from here — it is minted inside the container, where
      // the short-lived value belongs, and this service never holds one.
      //
      // NOT gated on the run's phase. A release-request (QA) run publishes too — the java-service
      // archetype PUTs its userflows bundle to the docs store from a QA step — so the only honest
      // gate is the one above: has this run a commission to mint with.
      env.put("QITS_PUBLISH_TOKEN_COMMAND", CiDaemonLauncher.PUBLISH_TOKEN_COMMAND);
    }
    // THE EDGE PLANE'S CREDENTIAL, and it replaces the whole block above rather than joining it. A
    // step outside the swarm cannot reach the idp's alias to mint from a pair, and the edge
    // introspects a qits_tok_ itself — as a bearer, and as the password of git's and docker's Basic
    // — so this run's ci-run token is everything such a step presents, and nothing that names the
    // internal idp is sent at all: no pair, no token url, no git auth host, no audience. BOOTSTRAP's
    // QITS_TOKEN branch turns it into the same four things the pair becomes (git helper, publish
    // command, maven settings, npmrc); the token itself is the run's and dies with it
    // (RunCommissions.release), so it is sent here rather than minted there.
    if (token != null) {
      env.put("QITS_TOKEN", value(token.token()));
      env.put("QITS_TOKEN_SUBJECT", value(token.subject()));
      env.put("GIT_CONFIG_GLOBAL", "/tmp/qits-gitconfig");
      env.put("QITS_PUBLISH_TOKEN_COMMAND", CiDaemonLauncher.PUBLISH_TOKEN_COMMAND);
    }
    if (spec.docker() || spec.build()) {
      // The two flags are the two generations of the same declaration — `docker: true` mounts the
      // socket and `build: true` does not — and everything in this block is the BUILD-MODE
      // environment both need. Only the socket differs, at the spec's hostDockerSocket below.
      if (spec.docker()) {
        // BuildKit, demanded rather than preferred, on the legacy socket arm only — a buildctl
        // step has no docker CLI in the loop for either flag to steer. Every step image ships
        // buildx as of qits-oci 2026.814.110556, so a legacy build here is a silent fallback
        // rather than an image that has no choice — and a silent fallback is what quietly loses a
        // --secret mount or a cache export. DOCKER_BUILDKIT=1 turns that into a loud error
        // instead. The second flag keeps a push a single manifest: buildx attaches provenance and
        // SBOM attestations by default, which makes the push an index the platform registry does
        // not expect.
        env.put("DOCKER_BUILDKIT", "1");
        env.put("BUILDX_NO_DEFAULT_ATTESTATIONS", "1");
      }
      // The platform-builder pair, and the kill switch's whole reach. ON, the step composes a
      // buildctl push ref from $QITS_BUILD_REGISTRY and $BUILDKIT_HOST arrives from
      // qits-containers, which owns the builder and its address — this service deliberately does
      // not spell an address it does not own (the docker-socket-path lesson). OFF, both keys are
      // sent EMPTY, the mirror pair's off value, and the empty BUILDKIT_HOST is load-bearing:
      // qits-containers fills the key in only when the caller left it absent, so empty is how this
      // service says "do not". A converted recipe then fails loudly at its first buildctl call
      // rather than silently building through the socket it still holds; an unconverted one reads
      // neither variable and is untouched.
      env.put("QITS_BUILD_REGISTRY", settings.buildkitEnabled() ? value(plane.buildRegistryHost()) : "");
      if (!settings.buildkitEnabled()) {
        env.put("BUILDKIT_HOST", "");
      }
      // And this run's own push credential — the document, the directory the bootstrap writes it
      // into, and the pair itself for a BuildKit secret mount. Commissioned at the run's first step
      // and reused by every later one; absent whole on a deployment with no oidc client, where a
      // step container's environment is exactly what it always was.
      if (commission != null) {
        env.put("DOCKER_CONFIG", CiDaemonLauncher.REGISTRY_AUTH_DIR);
        env.put("QITS_CI_REGISTRY_AUTH_CONFIG", registryAuthConfig(commission, plane.authHosts()));
        env.put("QITS_COMMISSIONED_CLIENT_ID", value(commission.clientId()));
        env.put("QITS_COMMISSIONED_CLIENT_SECRET", value(commission.secret()));
      } else if (token != null) {
        // The same document for the edge plane: one login per public registry host, each
        // `token:<qits_tok_…>` — the Basic form the edge's docker realm introspects. No pair beside
        // it, for the block above's reason.
        env.put("DOCKER_CONFIG", CiDaemonLauncher.REGISTRY_AUTH_DIR);
        env.put(
            "QITS_CI_REGISTRY_AUTH_CONFIG",
            registryAuthConfig(TOKEN_LOGIN, token.token(), plane.authHosts()));
      }
    }
    // Run-scoped extras, LAST and in sorted key order. Today these are the four QITS_EVENT_* of an
    // event-triggered run and the map is empty on every push; none of them is ever repo-authored.
    // Last is the construction the argv had, where a repeated --env meant the later one won, so a
    // map's later put means exactly what the old argv meant. Sorted because the whole request is
    // asserted literally by CiDaemonLauncherTest, and a set's iteration order is not a thing to
    // assert against.
    for (Map.Entry<String, String> extra : new TreeMap<>(spec.env()).entrySet()) {
      env.put(extra.getKey(), value(extra.getValue()));
    }

    return new Spec(
        spec.image(),
        // The entrypoint and the bootstrap, as two lists rather than a command line. Nothing is
        // concatenated on either side of the wire, so the zero-interpolation property BOOTSTRAP
        // has always claimed now holds BY CONSTRUCTION rather than by inspection of an argv.
        List.of("/bin/sh"),
        List.of("-c", CiDaemonLauncher.BOOTSTRAP),
        env,
        // The human hint. It selects nothing any more — see RUN_LABEL.
        Map.of(CiDaemonLauncher.RUN_LABEL, value(spec.runId())),
        plane.network(),
        null,
        plane.extraHosts(),
        null,
        null,
        // The declared opt-in, and the only thing about a step that ever changes this request.
        spec.docker(),
        // The step's script is repo-controlled: drop privileges and bound the blast radius. The
        // daemon runs inside this sandbox and the script is its child, so these bound both.
        new Security(
            true,
            true,
            settings.memoryLimit(),
            settings.memoryLimit(),
            settings.pidsLimit(),
            settings.cpus(),
            settings.oomScoreAdj()),
        null,
        CiDaemonLauncher.containerName(spec.runId(), spec.stepIndex()),
        // The other declared opt-in, and the reason it is here rather than in the script: the
        // sandbox above takes CAP_SETUID, CAP_SETGID and CAP_CHOWN away, so `su` and `chown`
        // both fail inside the container whatever it tries. Empty is the image's own default.
        // The parser refuses this beside `docker`, so a socket-holding step is always root.
        value(spec.user()),
        // No tini: the daemon is PID 1, exactly as it was before the spec could say otherwise.
        // Known cost, known already: killed orphans stay zombies until the container exits.
        // Flipping this on is a behavior decision for its own change, not this call site's.
        null);
  }

  /**
   * The smart-HTTP url of a repository as a step container clones it — see {@link
   * CiDaemonLauncher#cloneUrl}, which is this with the launcher's own base.
   */
  static String cloneUrl(String containerGitUrl, CiRepoRef repo) {
    String base = containerGitUrl.replaceAll("/+$", "") + "/git/";
    return repo.named() ? base + repo.projectId() + "/" + repo.name() : base + repo.repoId();
  }

  /**
   * The docker {@code config.json} a publishing step logs in with, built from this run's own
   * commissioned pair.
   *
   * <p><b>The scope is the decision that survived the cutover.</b> Only a step in BUILD MODE is
   * handed this — {@code docker: true} or {@code build: true}, which is the shape the archetypes
   * publish images from: the credential exists for a push, and a step that cannot build has nothing
   * to push with, so the narrow scope costs nothing and keeps the secret out of every container
   * that cannot use it.
   *
   * <p><b>One entry per host in {@code hosts} — {@code CiDaemonLauncher.authHosts}' union — all
   * carrying the same pair.</b> The docker
   * client picks a login by registry hostname and buildctl does the same, so a build that pulls
   * from one host and pushes to another needs both named — which is exactly the shipped shape
   * rather than an edge case, since {@code $QITS_REGISTRY} and {@code $QITS_BUILD_REGISTRY} are two
   * network positions of one registry and only the second is where a push goes.
   *
   * <p><b>Hand-written JSON, and it stays that way.</b> The document is fixed keys around one base64
   * value per host, and base64 has no character JSON escapes — so the only values that could need
   * quoting are the hostnames, deployment facts, escaped here anyway rather than trusted. A Jackson
   * mapper for a dozen tokens would be one more graph the native-image builder has to be told about,
   * which is the rule the whole {@code githost} package already follows.
   *
   * <p>The base64 is the docker CLI's own encoding of {@code id:secret}, the same bytes {@code
   * docker login} would store — <b>not</b> encryption, and no better protected than an environment
   * variable, which is what it travels as.
   */
  static String registryAuthConfig(IdpCommissioner.Commission commission, List<String> hosts) {
    return registryAuthConfig(commission.clientId(), commission.secret(), hosts);
  }

  /**
   * The user half of an edge step's registry login. Any value would do — the edge reads the
   * password and introspects it — so it names what the password is.
   */
  static final String TOKEN_LOGIN = "token";

  /** {@link #registryAuthConfig(IdpCommissioner.Commission, List)} for any {@code user:secret}. */
  static String registryAuthConfig(String user, String secret, List<String> hosts) {
    String auth =
        Base64.getEncoder()
            .encodeToString((user + ":" + secret).getBytes(StandardCharsets.UTF_8));
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

  private static String tokenUrl(String idpBase) {
    return value(idpBase).replaceAll("/+$", "") + "/token";
  }

  private static String gitAuthority(String gitBase) {
    try {
      URI uri = URI.create(gitBase);
      if (uri.getScheme() == null || uri.getRawAuthority() == null || uri.getUserInfo() != null) {
        throw new IllegalArgumentException("not an absolute git host URL");
      }
      return uri.getRawAuthority();
    } catch (RuntimeException badUrl) {
      throw new IllegalStateException("qits.ci.container-git-url must be an absolute URL", badUrl);
    }
  }

  private static String value(String text) {
    return text == null ? "" : text;
  }
}
