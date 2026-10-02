package eu.wohlben.qits.ci.runnerhost;

import eu.wohlben.qits.ci.control.CiRepoRef;
import eu.wohlben.qits.ci.idp.IdpCommissioner;
import eu.wohlben.qits.cirunner.protocol.WorkloadSpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * <b>What one step container is</b>: the whole workload spec, composed once, as the runner
 * protocol's {@link WorkloadSpec} — what {@link RunnerStepRunner} sends in a {@code Launch} and the
 * runner turns into its host's {@code docker run}. It used to be qits-containers' spec, composed
 * for two transports (the in-process executor asked qits-containers for the container); the runner
 * is the only transport since qits-506, so the spec is the protocol's own.
 *
 * <p><b>Pure</b>: settings, the address plane, the launch, the run's token and the runner's step
 * memory limit in, a spec out, no I/O. The token is commissioned by the caller (it is the one input
 * that can fail, and the caller records that failure as the step's own), everything configured that
 * is not an address arrives as {@link Settings}, read off {@link StepContainerSettings} at the
 * moment of asking, and every address arrives as a {@link StepAddressPlane}, composed from the
 * platform's public domain. {@code StepContainerSettingsTest} and {@code RunCommissioningTest}
 * assert the result literally.
 *
 * <p><b>What a spec never carries</b> (qits-515): a docker network, an extra host, a qits-net alias,
 * a commissioned client pair, a {@code QITS_GIT_AUTH_*} variable, or a per-container daemon secret.
 * A step reaches every service by its public name with {@code $QITS_TOKEN}, and its daemon is
 * matched to its launch by the launch id it names in its first frame.
 *
 * <p><b>No {@code qits.ci.runner} label is added here, and none may be.</b> That namespace is the
 * runner's own: it stamps {@code qits.ci.runner=<runner id>} and {@code qits.ci.runner.run} on every
 * container it starts (its boot sweep selects by them), and it refuses a spec whose labels reach
 * into it — so a label written here would fail every launch.
 */
public final class StepWorkloadSpecs {

  private StepWorkloadSpecs() {}

  /**
   * The deployment facts a spec is composed from that are NOT addresses — every one of them a config
   * value the launcher holds, or a value it derives from them ({@code artifactsCliVersion}), resolved
   * before it gets here. Every address is the {@link StepAddressPlane}'s.
   */
  public record Settings(
      String artifactsImageRepository,
      boolean mavenCentralMirrorEnabled,
      String artifactsCliPackage,
      String artifactsCliVersion,
      String memoryLimit,
      long pidsLimit,
      String cpus,
      Integer oomScoreAdj) {}

  /**
   * The step container's spec. {@code token} is this run's {@code ci-run} token, and it is
   * required: a step with no token can reach nothing, so the caller refuses to launch one rather
   * than composing it. {@code stepMemoryLimit} is the runner row's own cap, which
   * replaces the platform's {@code qits.ci.memory-limit} as memory AND memory-swap, so a runner's
   * steps still get no swap beyond their cap; null keeps the platform default.
   *
   * <p><b>The socket is the one privilege a repository can ask for.</b> A step declaring {@code
   * docker: true} sets {@code hostDockerSocket}, and the runner bind-mounts its host's socket at the
   * path the step image's CLI looks at by default. The sandbox stays exactly as it is for such a step
   * — {@code capDropAll} and {@code noNewPrivileges} cost a socket <em>client</em> nothing, and
   * keeping them unconditional keeps them meaning what they mean for every step that does not opt
   * in. They also do not make the opt-in safe: a step holding this socket is <b>root-equivalent on
   * the runner's host</b>, because those flags fence the step's own process tree and not what the
   * daemon will do on its behalf. That is accepted and it is per step, declared in the repository's
   * config where a diff shows it — see {@code AGENTS.md}'s untrusted-input section.
   *
   * <p>{@code buildPlane} is the step's {@code docker: || build:} — the one fact the protocol adds.
   */
  public static WorkloadSpec compose(
      Settings settings,
      StepAddressPlane plane,
      StepContainerSettings.LaunchSpec spec,
      IdpCommissioner.CommissionedToken token,
      String stepMemoryLimit) {
    Objects.requireNonNull(token, "a step is never composed without its run's token");
    Map<String, String> env = new LinkedHashMap<>();
    // The contract, as environment. The daemon needs all of it before a socket exists, which is why
    // none of it is a message.
    env.put("QITS_CI_DAEMON_ID", value(spec.daemonId()));
    // Its launch id, which the daemon names in its first frame. There is no secret beside it: the
    // run's token proves the run to qits-ci, and CiDaemonRegistry.admitByToken binds this id to
    // that token's subject.
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
    // Maven Central through qits-mirror, under both names a pipeline reads it by.
    // Empty is the deliberate off state, so the ternary writes "" rather than skipping the keys:
    // a pipeline reads "${QITS_MAVEN_CENTRAL_MIRROR_URL:-}" either way and empty deactivates the
    // settings profile at every consumer.
    env.put("QITS_MAVEN_CENTRAL_MIRROR_URL",
        settings.mavenCentralMirrorEnabled() ? value(plane.mavenCentralMirrorUrl()) : "");
    env.put("QITS_MAVEN_PROXY_URL",
        settings.mavenCentralMirrorEnabled() ? value(plane.mavenCentralMirrorUrl()) : "");
    env.put("QITS_DOCS_URL", value(plane.docsUrl()));
    // The store's own root, and the coordinate a composed release prelude downloads the qits CLI at.
    // The package is EMPTY-never-absent, so a deployment that has switched the CLI off hands
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
    // And where a step asks for its own repository to be released.
    env.put("QITS_WORKSPACES_URL", value(plane.workspacesUrl()));
    // THE STEP'S CREDENTIAL, and the only one: this run's ci-run token. The edge introspects a
    // qits_tok_ itself — as a bearer, and as the password of git's and docker's Basic — so the token
    // is everything a step presents, and nothing that names the idp is sent at all: no client pair,
    // no token url, no git auth host, no audience. BOOTSTRAP turns it into a git helper, a publish
    // command, maven settings and an npmrc; the token itself is the run's and dies with it
    // (RunCommissions.release).
    //
    // QITS_MAVEN_AUTH_USR/QITS_MAVEN_AUTH_PSW ride beside it for a reason BOOTSTRAP's own
    // -gs/DEPLOY_SETTINGS_FILE cannot reach (qits-441 follow-up, run ef26d331): every repository's
    // committed .qits-maven-settings.xml declares its OWN <server id=qits-maven-network>/<server
    // id=qits-central-proxy> reading ${env.QITS_MAVEN_AUTH_USR}/${env.QITS_MAVEN_AUTH_PSW}, and a
    // step's `-s .qits-maven-settings.xml` is a USER settings file — measured on Maven 3.9.12,
    // merging with a `-gs` GLOBAL settings file keeps BOTH files' <servers>, and for one server id
    // present in both, the USER file's entry wins. So the deploy settings this class writes into
    // MAVEN_ARGS as -gs never reaches those two ids at all; without these two variables the repo's
    // own entries resolve to two EMPTY strings, and the edge's WWW-Authenticate challenge is
    // answered with Basic "<anything>:" rather than the run's token, a 401 on
    // io.quarkus.platform:quarkus-bom and every other central artifact. Setting them is the whole
    // fix: the same bearer as $QITS_TOKEN, over the httpHeaders escape hatch, out of Maven's reach
    // entirely, this pair is the ordinary <username>/<password> credential a repo's OWN settings
    // already asked for — one profile Maven DOES send preemptively for Basic, unlike the header
    // form. $QITS_TOKEN_SUBJECT is a stable, readable username; the password is the token itself,
    // which is exactly what the edge's Basic realm introspects as a qits_tok_ regardless of the
    // username presented.
    env.put("QITS_TOKEN", value(token.token()));
    env.put("QITS_TOKEN_SUBJECT", value(token.subject()));
    env.put("QITS_MAVEN_AUTH_USR", value(token.subject()));
    env.put("QITS_MAVEN_AUTH_PSW", value(token.token()));
    env.put("GIT_CONFIG_GLOBAL", "/tmp/qits-gitconfig");
    env.put("QITS_PUBLISH_TOKEN_COMMAND", StepContainerSettings.PUBLISH_TOKEN_COMMAND);
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
      // The platform builder. The step composes a buildctl push ref from $QITS_BUILD_REGISTRY — the
      // registry's public name, the one address a builder outside the swarm can push to — and
      // $BUILDKIT_HOST arrives from the runner, which owns the builder and its address and fills
      // the key in when it is absent. This service does not spell an address it does not own.
      env.put("QITS_BUILD_REGISTRY", value(plane.registryHost()));
      // And this run's login: one entry per public registry host, each `token:<qits_tok_…>` — the
      // Basic form the edge's docker realm introspects — and the directory BOOTSTRAP writes the
      // document into.
      env.put("DOCKER_CONFIG", StepContainerSettings.REGISTRY_AUTH_DIR);
      env.put(
          "QITS_CI_REGISTRY_AUTH_CONFIG",
          registryAuthConfig(TOKEN_LOGIN, token.token(), withPullHost(plane, spec.image())));
    } else if (plane.imagePullHost(spec.image()) != null) {
      // THE PULL'S OWN LOGIN, for a step that is not a build (qits-479). The runner pulls the
      // step image with its own docker, from the registry's public vhost, which answers an anonymous
      // /v2 with 401 — so it reads this document for exactly that pull (docker --config) and only
      // ever for it. One entry, the one host the image is pulled from; no DOCKER_CONFIG beside it,
      // so the bootstrap writes no file and nothing in the container logs in: a step that cannot
      // build is handed no docker login, the scope decision above. Nothing new reaches the
      // container either — the document is QITS_TOKEN, already in this environment, in docker's
      // encoding. A step whose image is on somebody else's registry gets no key.
      env.put(
          "QITS_CI_REGISTRY_AUTH_CONFIG",
          registryAuthConfig(
              TOKEN_LOGIN, token.token(), List.of(plane.imagePullHost(spec.image()))));
    }
    // Run-scoped extras, LAST and in sorted key order. Today these are the four QITS_EVENT_* of an
    // event-triggered run and the map is empty on every push; none of them is ever repo-authored.
    // Last is the construction the argv had, where a repeated --env meant the later one won, so a
    // map's later put means exactly what the old argv meant. Sorted because the whole spec is
    // asserted literally by StepContainerSettingsTest, and a set's iteration order is not a thing
    // to assert against.
    for (Map.Entry<String, String> extra : new TreeMap<>(spec.env()).entrySet()) {
      env.put(extra.getKey(), value(extra.getValue()));
    }

    String memory = stepMemoryLimit == null ? settings.memoryLimit() : stepMemoryLimit;
    return new WorkloadSpec(
        // The image as the runner's docker pulls it: a platform registry host moved to its public
        // vhost, path, tag and digest kept.
        plane.imageReference(spec.image()),
        // The entrypoint and the bootstrap, as two lists rather than a command line. Nothing is
        // concatenated on either side of the wire, so the zero-interpolation property BOOTSTRAP
        // has always claimed holds BY CONSTRUCTION rather than by inspection of an argv.
        List.of("/bin/sh"),
        List.of("-c", StepContainerSettings.BOOTSTRAP),
        env,
        // The human hint. It selects nothing — see RUN_LABEL.
        Map.of(StepContainerSettings.RUN_LABEL, value(spec.runId())),
        // No docker network and no extra host: a step reaches everything by its public name.
        null,
        List.of(),
        // The other declared opt-in, and the reason it is here rather than in the script: the
        // sandbox below takes CAP_SETUID, CAP_SETGID and CAP_CHOWN away, so `su` and `chown`
        // both fail inside the container whatever it tries. Empty is the image's own default.
        // The parser refuses this beside `docker`, so a socket-holding step is always root.
        value(spec.user()),
        // The declared opt-in, and the only thing about a step that ever changes the sandbox.
        spec.docker(),
        // The step's script is repo-controlled: drop privileges and bound the blast radius. The
        // daemon runs inside this sandbox and the script is its child, so these bound both.
        true,
        true,
        memory,
        memory,
        settings.pidsLimit(),
        settings.cpus(),
        settings.oomScoreAdj(),
        StepContainerSettings.containerName(spec.runId(), spec.stepIndex()),
        spec.docker() || spec.build());
  }

  /**
   * The smart-HTTP url of a repository, as a step container clones it: {@code
   * <base>/git/<projectId>/<repoName>} when the run carries the public coordinate, and the
   * id-addressed {@code <base>/git/<repoId>} when it does not. {@code /git} is the codebase's
   * second-level segment for the git wire protocol, so it lives here; the base names only which
   * service hosts it. It is the daemon's {@code $QITS_CI_REPOSITORY_URL} — a value the container
   * clones from, never a word in a command line.
   */
  static String cloneUrl(String containerGitUrl, CiRepoRef repo) {
    String base = containerGitUrl.replaceAll("/+$", "") + "/git/";
    return repo.named() ? base + repo.projectId() + "/" + repo.name() : base + repo.repoId();
  }

  /**
   * The user half of a step's registry login.  /**
   * The user half of an edge step's registry login. Any value would do — the edge reads the
   * password and introspects it — so it names what the password is.
   */
  static final String TOKEN_LOGIN = "token";

  /**
   * The docker {@code config.json} a step's registry login is carried in: one entry per host in
   * {@code hosts}, all carrying the same {@code user:secret}. The docker client picks a login by
   * registry hostname and buildctl does the same, so a build that pulls from the mirror and pushes
   * to the registry needs both named.
   *
   * <p><b>Only a step in BUILD MODE gets the whole document</b> — {@code docker: true} or {@code
   * build: true} — since the credential exists for a push, and a step that cannot build has nothing
   * to push with; any other step gets at most the one entry its own image pull needs.
   *
   * <p><b>Hand-written JSON, and it stays that way.</b> The document is fixed keys around one base64
   * value per host, and base64 has no character JSON escapes — so the only values that could need
   * quoting are the hostnames, escaped here anyway rather than trusted. A Jackson mapper for a dozen
   * tokens would be one more graph the native-image builder has to be told about.
   *
   * <p>The base64 is the docker CLI's own encoding of {@code user:secret}, the same bytes {@code
   * docker login} would store — <b>not</b> encryption, and no better protected than an environment
   * variable, which is what it travels as.
   */
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

  /**
   * {@code plane}'s login hosts with the host the step image is pulled from added when they lack it,
   * so a build step's document always logs in wherever its own image comes from. The two coincide
   * by construction (an image is only ever moved onto the registry or mirror vhost,
   * and both are logged into), so this is a guard rather than a change.
   */
  private static List<String> withPullHost(StepAddressPlane plane, String image) {
    String pullHost = plane.imagePullHost(image);
    if (pullHost == null || plane.authHosts().contains(pullHost)) {
      return plane.authHosts();
    }
    List<String> hosts = new java.util.ArrayList<>(plane.authHosts());
    hosts.add(pullHost);
    return hosts;
  }

  private static String value(String text) {
    return text == null ? "" : text;
  }
}
