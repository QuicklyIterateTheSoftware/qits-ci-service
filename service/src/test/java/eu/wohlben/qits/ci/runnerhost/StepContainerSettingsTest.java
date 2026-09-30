package eu.wohlben.qits.ci.runnerhost;

import eu.wohlben.qits.ci.control.CiRepoRef;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.runnerhost.StepContainerSettings.LaunchSpec;
import eu.wohlben.qits.cirunner.protocol.WorkloadSpec;
import eu.wohlben.qits.platformaccess.cli.PlatformAccessCliBinary;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Workload-spec and bootstrap assembly only — a real runner is {@code RunnerStepRunnerTest}'s
 * subject.
 *
 * <p><b>The assertion is the whole spec, by equality.</b> It used to be the whole argv, a flat list
 * of eighty strings, then the whole qits-containers request; the same claim over a record tree is
 * one {@code assertEquals} against a literal, and it still fails when a field goes missing rather
 * than when someone remembers to check for it.
 *
 * <p><b>Deliberately does not exercise {@link StepContainerSettings#daemonVersion()}.</b> That
 * method delegates to the injected {@code CiDaemonPins}, a real CDI bean this plain-construction
 * test never wires up; its coverage lives in {@code CiDaemonPinsTest} and {@code CiDaemonPinTest}
 * instead. This class stays about pure spec assembly, which is why it can be {@code new
 * StepContainerSettings()} with fields set by hand rather than a {@code @QuarkusTest} — and why it
 * needs no client at all: nothing here sends anything. {@link StepWorkloadSpecs#compose} is pure, so
 * every spec here is composed directly rather than through a launch this class no longer offers
 * (qits-506: launching, reaping and the boot reap are the runner's, not qits-ci's).
 */
public class StepContainerSettingsTest {

  /** The shipped settings, with the CLI package the platform really ships. */
  private StepContainerSettings launcher() {
    StepContainerSettings launcher = StepFixtures.shippedLauncher();
    launcher.artifactsCliPackage = "qits-platform-access-cli";
    return launcher;
  }

  /**
   * Composes a step the way {@code RunnerStepRunner} does: the addresses of {@code example.org}, and
   * the run's token — a step is never composed without one (qits-515). The token's own lifecycle is
   * {@code RunCommissioningTest}'s.
   */
  private static WorkloadSpec compose(StepContainerSettings launcher, LaunchSpec spec) {
    return StepWorkloadSpecs.compose(
        launcher.workloadSettings(), StepFixtures.plane(launcher), spec, StepFixtures.token(), null);
  }

  private WorkloadSpec compose(LaunchSpec spec) {
    return compose(launcher(), spec);
  }

  private final LaunchSpec spec =
      new LaunchSpec(
          "0123456789abcdef-run",
          2,
          CiRepoRef.of("repo-1"),
          "main",
          "cafebabe",
          "maven:3.9",
          "daemon-7",
          "/artifacts/daemons/qits-ci-daemon/deadbeef",
          0,
          false,
          false,
          "",
          Map.of());

  /** The same step, having declared {@code docker: true} — the only difference anywhere. */
  private LaunchSpec publishing() {
    return withDocker(spec, true);
  }

  private static LaunchSpec withDocker(LaunchSpec original, boolean docker) {
    return new LaunchSpec(
        original.runId(),
        original.stepIndex(),
        original.repo(),
        original.branch(),
        original.sha(),
        original.image(),
        original.daemonId(),
        original.daemonBinaryUrl(),
        original.stepTimeoutSeconds(),
        docker,
        original.build(),
        original.user(),
        original.env());
  }

  /** The same step, having declared {@code build: true} — the socketless build declaration. */
  private LaunchSpec buildStep() {
    return new LaunchSpec(
        spec.runId(),
        spec.stepIndex(),
        spec.repo(),
        spec.branch(),
        spec.sha(),
        spec.image(),
        spec.daemonId(),
        spec.daemonBinaryUrl(),
        spec.stepTimeoutSeconds(),
        false,
        true,
        spec.user(),
        spec.env());
  }

  /** The same step, having declared {@code user: build} — again the only difference anywhere. */
  private LaunchSpec asBuildUser() {
    return new LaunchSpec(
        spec.runId(),
        spec.stepIndex(),
        spec.repo(),
        spec.branch(),
        spec.sha(),
        spec.image(),
        spec.daemonId(),
        spec.daemonBinaryUrl(),
        spec.stepTimeoutSeconds(),
        spec.docker(),
        spec.build(),
        "build",
        spec.env());
  }

  /** The environment every step container gets, in the order the spec writes it. */
  private static Map<String, String> contractEnv() {
    Map<String, String> env = new LinkedHashMap<>();
    env.put("QITS_CI_DAEMON_ID", "daemon-7");
    env.put("QITS_CI_DAEMON_URL", "wss://ci.qits.example.org/ci/daemon");
    env.put(
        "QITS_CI_DAEMON_BINARY_URL",
        "https://registry.qits.example.org/artifacts/daemons/qits-ci-daemon/deadbeef");
    env.put("QITS_CI_REPOSITORY_URL", "https://githost.qits.example.org/git/repo-1");
    env.put("QITS_CI_BRANCH", "main");
    env.put("QITS_CI_SHA", "cafebabe");
    env.put("QITS_CI_REPO_ID", "repo-1");
    // The public coordinate, EMPTY rather than absent on an id-addressed run: one shape for a step
    // to read, whichever way its run was announced.
    env.put("QITS_CI_PROJECT_ID", "");
    env.put("QITS_CI_REPO_NAME", "");
    env.put("CI", "true");
    env.put("QITS_CI", "true");
    env.put("QITS_REGISTRY", "registry.qits.example.org");
    env.put("QITS_IMAGE_REPOSITORY", "qits");
    env.put("QITS_NPM_REGISTRY_URL", "https://registry.qits.example.org/artifacts/npm/npm/");
    env.put("QITS_NPM_PROXY_URL", "https://mirror.qits.example.org/npm/npmjs/");
    env.put("QITS_MAVEN_REGISTRY_URL", "https://registry.qits.example.org/artifacts/maven/maven");
    // The mirror under both names a pipeline reads it by, on /mirror/maven, the mirror's own route.
    env.put(
        "QITS_MAVEN_CENTRAL_MIRROR_URL", "https://mirror.qits.example.org/mirror/maven/central");
    env.put("QITS_MAVEN_PROXY_URL", "https://mirror.qits.example.org/mirror/maven/central");
    env.put("QITS_DOCS_URL", "https://registry.qits.example.org/artifacts/docs/docs");
    // The store's root, and the coordinate a composed release prelude downloads the qits CLI at. The
    // version is qits-ci's own pinned dependency's constant rather than a literal, because a literal
    // here would be a second place the pin is written down and the pin moving would be a red suite
    // rather than a moved pin.
    env.put("QITS_ARTIFACTS_URL", "https://registry.qits.example.org");
    env.put("QITS_ARTIFACTS_CLI_PACKAGE", "qits-platform-access-cli");
    env.put("QITS_ARTIFACTS_CLI_VERSION", PlatformAccessCliBinary.VERSION);
    env.put("QITS_WORKSPACES_URL", "https://workspaces.qits.example.org");
    // The run's token, and what is derived from it for the step's tools.
    env.put("QITS_TOKEN", StepFixtures.TOKEN);
    env.put("QITS_TOKEN_SUBJECT", StepFixtures.SUBJECT);
    env.put("QITS_MAVEN_AUTH_USR", StepFixtures.SUBJECT);
    env.put("QITS_MAVEN_AUTH_PSW", StepFixtures.TOKEN);
    env.put("GIT_CONFIG_GLOBAL", "/tmp/qits-gitconfig");
    env.put("QITS_PUBLISH_TOKEN_COMMAND", "/tmp/qits-publish-token");
    return env;
  }

  /**
   * The whole spec, written out. Every field is here on purpose, including {@code user} and the
   * {@code network} and extra hosts a step no longer has, which is why this compares a literal rather than spot-checking fields: a field lost in
   * a refactor is invisible everywhere else until it is invisible in production.
   */
  @Test
  public void buildsTheWholeWorkloadSpec() {
    assertEquals(
        new WorkloadSpec(
            "maven:3.9",
            List.of("/bin/sh"),
            List.of("-c", StepContainerSettings.BOOTSTRAP),
            contractEnv(),
            Map.of("qits.ci.run", "0123456789abcdef-run"),
            null,
            List.of(),
            null,
            false,
            true,
            true,
            "4g",
            "4g",
            2048L,
            "2",
            1000,
            "qits-ci-01234567-412621e6-2",
            false),
        compose(spec));
  }

  @Test
  public void aNamedRunClonesByProjectAndNameAndSaysSoInTheEnvironment() {
    // The post-cutover arm. Three values move together: the clone url becomes the public address,
    // and the two new variables carry the pair a pipeline reads. QITS_CI_REPO_ID keeps announcing
    // the STORAGE id — every pipeline in the estate still reads it, and a later work package is
    // what moves them off it.
    LaunchSpec named =
        new LaunchSpec(
            spec.runId(),
            spec.stepIndex(),
            CiRepoRef.of("2f1c9b3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f", "qits", "qits-blobstore"),
            spec.branch(),
            spec.sha(),
            spec.image(),
            spec.daemonId(),
                spec.daemonBinaryUrl(),
            spec.stepTimeoutSeconds(),
            spec.docker(),
            spec.build(),
            spec.user(),
            spec.env());

    Map<String, String> env = compose(named).env();
    assertEquals(
        "https://githost.qits.example.org/git/qits/qits-blobstore",
        env.get("QITS_CI_REPOSITORY_URL"));
    assertEquals("qits", env.get("QITS_CI_PROJECT_ID"));
    assertEquals("qits-blobstore", env.get("QITS_CI_REPO_NAME"));
    assertEquals("2f1c9b3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f", env.get("QITS_CI_REPO_ID"));
  }

  @Test
  public void aStepThatDeclaredDockerGetsTheHostSocketAndOnlyThat() {
    WorkloadSpec plain = compose(spec);
    WorkloadSpec withDocker = compose(publishing());

    // The flag is set, and the runner is what turns it into a mount at the path the step image's
    // CLI looks at — which is why there is no path in this spec at all.
    assertTrue(withDocker.hostDockerSocket());

    // And the sandbox does not relax for a publish step: capDropAll and noNewPrivileges cost a
    // socket client nothing and keeping them unconditional is what keeps them meaning something for
    // the steps that never opt in. The diff is the socket plus the build environment (the two
    // mode flags, $QITS_BUILD_REGISTRY and the registry login), which is a build MODE rather than
    // a privilege —
    // asserted as an exact residue rather than as spot checks, the same claim the old argv test
    // made by deleting list elements and comparing the rest.
    assertNotEquals(plain, withDocker);
    assertEquals(plain, withoutSocket(withoutBuildKit(withDocker)));
    assertTrue(withDocker.capDropAll());
    assertTrue(withDocker.noNewPrivileges());
  }

  /** The build environment taken back out, so the residue is comparable to a plain step's. */
  private static WorkloadSpec withoutBuildKit(WorkloadSpec original) {
    Map<String, String> env = new LinkedHashMap<>(original.env());
    env.remove("DOCKER_BUILDKIT");
    env.remove("BUILDX_NO_DEFAULT_ATTESTATIONS");
    env.remove("QITS_BUILD_REGISTRY");
    env.remove("DOCKER_CONFIG");
    env.remove("QITS_CI_REGISTRY_AUTH_CONFIG");
    return withEnv(original, env);
  }

  /** The one field under test, put back to what a step that declared nothing would have sent. */
  private static WorkloadSpec withoutSocket(WorkloadSpec original) {
    return new WorkloadSpec(
        original.image(),
        original.entrypoint(),
        original.args(),
        original.env(),
        original.labels(),
        original.network(),
        original.extraHosts(),
        original.user(),
        false,
        original.capDropAll(),
        original.noNewPrivileges(),
        original.memory(),
        original.memorySwap(),
        original.pidsLimit(),
        original.cpus(),
        original.oomScoreAdj(),
        original.name(),
        // buildPlane follows docker/build, not the socket alone; a plain step declares neither.
        false);
  }

  /** The one field under test, put back to what a step that declared nothing would have sent. */
  private static WorkloadSpec withoutUser(WorkloadSpec original) {
    return new WorkloadSpec(
        original.image(),
        original.entrypoint(),
        original.args(),
        original.env(),
        original.labels(),
        original.network(),
        original.extraHosts(),
        null,
        original.hostDockerSocket(),
        original.capDropAll(),
        original.noNewPrivileges(),
        original.memory(),
        original.memorySwap(),
        original.pidsLimit(),
        original.cpus(),
        original.oomScoreAdj(),
        original.name(),
        original.buildPlane());
  }

  private static WorkloadSpec withEnv(WorkloadSpec original, Map<String, String> env) {
    return new WorkloadSpec(
        original.image(),
        original.entrypoint(),
        original.args(),
        env,
        original.labels(),
        original.network(),
        original.extraHosts(),
        original.user(),
        original.hostDockerSocket(),
        original.capDropAll(),
        original.noNewPrivileges(),
        original.memory(),
        original.memorySwap(),
        original.pidsLimit(),
        original.cpus(),
        original.oomScoreAdj(),
        original.name(),
        original.buildPlane());
  }

  @Test
  public void aDockerStepCarriesTheBuildRegistryAndNoBuilderAddress() {
    Map<String, String> env = compose(publishing()).env();
    assertEquals("registry.qits.example.org", env.get("QITS_BUILD_REGISTRY"));
    // No BUILDKIT_HOST: the address is the runner's own to fill — an address spelled here too would
    // be the two-copies drift the docker-socket-path deletion already paid for once.
    assertFalse(env.containsKey("BUILDKIT_HOST"));
  }

  @Test
  public void aBuildStepGetsTheBuildEnvironmentAndNeverTheSocket() {
    // The end state: build: true is docker: true minus the root-equivalence. Same registry
    // variable, same login — and no socket, no mode flags (they steer a docker CLI a buildctl step
    // never runs).
    WorkloadSpec request = compose(buildStep());
    assertFalse(request.hostDockerSocket());
    Map<String, String> env = request.env();
    assertEquals("registry.qits.example.org", env.get("QITS_BUILD_REGISTRY"));
    assertEquals("/tmp/qits-ci-registry-auth", env.get("DOCKER_CONFIG"));
    assertFalse(env.containsKey("BUILDKIT_HOST"), "the address stays the runner's to fill");
    assertFalse(env.containsKey("DOCKER_BUILDKIT"));
    assertFalse(env.containsKey("BUILDX_NO_DEFAULT_ATTESTATIONS"));
    for (Map.Entry<String, String> entry : env.entrySet()) {
      assertFalse(entry.getValue().contains("docker.sock"), "no socket may ride in: " + entry);
    }
  }

  @Test
  public void aStepThatDeclaredNothingGetsNoDockerSocketAtAll() {
    // THIS is the security assertion of the pair — the absence, not the presence. A step's script is
    // repo-controlled code and the docker socket is root on the host, so "no socket unless the config
    // said so" is the invariant, and an accidental unconditional flag would be invisible everywhere
    // else in this repository until it was invisible in production.
    WorkloadSpec workload = compose(spec);
    assertFalse(workload.hostDockerSocket());
    // And nothing else may smuggle one in: a socket path hidden in an environment value the daemon
    // would find. (There is no volume or mount field on the wire at all any more — see WorkloadSpec's
    // own javadoc: a field a step never asks for is absent rather than carried empty.)
    for (Map.Entry<String, String> entry : workload.env().entrySet()) {
      assertFalse(
          entry.getValue().contains("docker.sock"),
          "no step may see a docker socket it did not ask for: " + entry);
    }
  }

  @Test
  public void aStepThatDeclaredAUserRunsAsThatUserAndNothingElseChanges() {
    WorkloadSpec plain = compose(spec);
    WorkloadSpec asBuild = compose(asBuildUser());

    assertEquals("build", asBuild.user());

    // Exactly one field, the same claim the socket pair makes: the sandbox does not relax for a
    // step that dropped root, and nothing else about the spec moves. The declaration is here at
    // all because the container cannot do it itself — --cap-drop=ALL leaves no CAP_SETUID for `su`
    // and no CAP_CHOWN for the checkout, measured 2026-08-12 on the runner's docker.
    assertNotEquals(plain, asBuild);
    assertEquals(plain, withoutUser(asBuild));
    assertTrue(asBuild.capDropAll());
    assertTrue(asBuild.noNewPrivileges());
  }

  @Test
  public void aStepThatDeclaredNoUserRunsAsTheImagesOwn() {
    // The absence, asserted on its own. An unset user means the image's default, and a value that
    // appeared unasked would run every existing pipeline as somebody its image never provisioned —
    // which fails deep inside a build with a permission error rather than at the launch. WorkloadSpec
    // normalizes a blank user to null in its canonical constructor.
    assertNull(compose(spec).user());
    assertNull(compose(publishing()).user());
  }

  @Test
  public void everyStepIsToldWhereAPublishedImageGoes() {
    // Injected unconditionally, opted in or not: "which registry" must never be a literal in a
    // repository's pipeline. With $QITS_CI_SHA these two are the whole tag convention
    // <registry>/<repository>/<application>:<sha>, and the registry is its public name.
    for (LaunchSpec each : List.of(spec, publishing())) {
      Map<String, String> env = compose(each).env();
      assertEquals("registry.qits.example.org", env.get("QITS_REGISTRY"));
      assertEquals("qits", env.get("QITS_IMAGE_REPOSITORY"));
      assertEquals("cafebabe", env.get("QITS_CI_SHA"));
    }
  }

  @Test
  public void everyStepIsToldWhereNpmPackagesComeFromAndGoTo() {
    // Also unconditional, and for the same reason. These two are dialled by the step container
    // itself, so a publish to them is an ordinary HTTP step that never declares `docker: true`.
    for (LaunchSpec each : List.of(spec, publishing())) {
      Map<String, String> env = compose(each).env();
      assertEquals(
          "https://registry.qits.example.org/artifacts/npm/npm/", env.get("QITS_NPM_REGISTRY_URL"));
      assertEquals("https://mirror.qits.example.org/npm/npmjs/", env.get("QITS_NPM_PROXY_URL"));
    }
  }

  @Test
  public void everyStepIsToldWhereMavenPackagesComeFromAndGoTo() {
    for (LaunchSpec each : List.of(spec, publishing())) {
      assertEquals(
          "https://registry.qits.example.org/artifacts/maven/maven",
          compose(each).env().get("QITS_MAVEN_REGISTRY_URL"));
    }
  }

  @Test
  public void bothMavenCentralKeysNameTheMirrorOnItsOwnRoute() {
    // /mirror is the mirror's own route; /artifacts routes to the hosted registry. A step's own
    // maven and a build it starts reach the same public name, so the two keys carry one value.
    for (LaunchSpec each : List.of(spec, publishing())) {
      Map<String, String> env = compose(each).env();
      assertEquals(
          "https://mirror.qits.example.org/mirror/maven/central",
          env.get("QITS_MAVEN_CENTRAL_MIRROR_URL"));
      assertEquals(
          "https://mirror.qits.example.org/mirror/maven/central", env.get("QITS_MAVEN_PROXY_URL"));
    }
  }

  @Test
  public void aDeploymentThatCannotReachTheMirrorInjectsTheCentralPairEMPTY() {
    // Empty, never absent, is the off state: every .qits-maven-settings.xml activates its
    // central-proxy profile only on a non-empty value, so an empty pair means every build resolves
    // Maven Central directly — the arm a bootstrap is on while the mirror is not started yet. The
    // keys must still be PRESENT, because a pipeline reads "${QITS_MAVEN_CENTRAL_MIRROR_URL:-}"
    // under `set -u` and one shape for a step to read is the estate's rule for optional values.
    StepContainerSettings launcher = launcher();
    launcher.mavenCentralMirrorEnabled = false;
    for (LaunchSpec each : List.of(spec, publishing())) {
      Map<String, String> env = compose(launcher, each).env();
      assertEquals("", env.get("QITS_MAVEN_CENTRAL_MIRROR_URL"));
      assertEquals("", env.get("QITS_MAVEN_PROXY_URL"));
    }
  }

  @Test
  public void everyStepIsToldWhereItsDocumentationGoes() {
    // Including the `docs` namespace segment: there is one docs repository and a pipeline that got
    // to name one could publish into a namespace nothing serves.
    for (LaunchSpec each : List.of(spec, publishing())) {
      assertEquals(
          "https://registry.qits.example.org/artifacts/docs/docs",
          compose(each).env().get("QITS_DOCS_URL"));
    }
  }

  @Test
  public void everyStepIsToldTheArtifactStoresRootAndItsCliPackage() {
    // THE ROOT ENDS THREE STRING-CHOPPING DERIVATIONS. Every pipeline that publishes an SBOM, a
    // daemon binary or a docs bundle today takes one of the package roots and cuts the path off with
    // its own sed expression; there is one origin, and one variable says so: the store's public
    // name, which is never empty — it is composed from the domain rather than read from a key.
    // AND THE CLI'S VERSION TRAVELS TOO, which is the half that used to be missing. It is qits-ci's
    // pinned dependency's constant, so which qits CLI a composed release step runs is a pom line
    // this repository's release request gated — not whatever was latest in the store at the moment
    // the step started, which is what broke every composed release on the platform at once on
    // 2026-09-13.
    for (LaunchSpec each : List.of(spec, publishing())) {
      Map<String, String> env = compose(each).env();
      assertEquals("https://registry.qits.example.org", env.get("QITS_ARTIFACTS_URL"));
      assertEquals("qits-platform-access-cli", env.get("QITS_ARTIFACTS_CLI_PACKAGE"));
      assertEquals(PlatformAccessCliBinary.VERSION, env.get("QITS_ARTIFACTS_CLI_VERSION"));
    }
  }

  @Test
  public void theCliVersionIsThePinUnlessAnOperatorOverridesIt() {
    // THE PIN IS THE DEFAULT AND THE OVERRIDE IS THE EXCEPTION, which is the whole posture: an
    // absent or blank override is the ordinary state, and a set one is an operator deliberately
    // running a CLI this repository's gate never saw.
    StepContainerSettings pinned = launcher();
    assertEquals(PlatformAccessCliBinary.VERSION, pinned.artifactsCliVersion());

    StepContainerSettings blank = launcher();
    blank.artifactsCliVersionOverride = java.util.Optional.of("   ");
    assertEquals(
        PlatformAccessCliBinary.VERSION,
        blank.artifactsCliVersion(),
        "a blank override is the same as none — it must never download a version named ''");

    StepContainerSettings overridden = launcher();
    overridden.artifactsCliVersionOverride = java.util.Optional.of("2026.101.1");
    assertEquals("2026.101.1", overridden.artifactsCliVersion());
    assertEquals(
        "2026.101.1",
        compose(overridden, spec).env().get("QITS_ARTIFACTS_CLI_VERSION"),
        "and it is what the step container is really told, not just what the method answers");
  }

  @Test
  public void theCliVersionIsNeverBlankEvenWithTheCliSwitchedOff() {
    // THE PRELUDE RELIES ON THIS. Its `:?` guard on $QITS_ARTIFACTS_CLI_VERSION is meant to name one
    // cause only — a qits-ci older than the pin launched this step — so this side must never be the
    // one that sends an empty value. The package's off state is the package's alone; the version is
    // a constant and has no off state.
    StepContainerSettings off = launcher();
    off.artifactsCliPackage = "";
    Map<String, String> env = compose(off, spec).env();
    assertEquals("", env.get("QITS_ARTIFACTS_CLI_PACKAGE"));
    assertEquals(PlatformAccessCliBinary.VERSION, env.get("QITS_ARTIFACTS_CLI_VERSION"));
  }

  @Test
  public void aBlankCliPackageShipsTheVariableEmptyRatherThanTrimmedOrMissing() {
    // EMPTY, never absent and never the untrimmed operator value. A composed release prelude skips
    // the fetch on empty, and a composed postlude that really needs the CLI fails on its own `:?`
    // guard naming the one config key — which is a sentence about configuration rather than a curl
    // error nobody can place.
    StepContainerSettings off = launcher();
    off.artifactsCliPackage = "";
    assertEquals("", compose(off, spec).env().get("QITS_ARTIFACTS_CLI_PACKAGE"));

    StepContainerSettings blank = launcher();
    blank.artifactsCliPackage = "  ";
    assertEquals("", compose(blank, spec).env().get("QITS_ARTIFACTS_CLI_PACKAGE"));
  }

  @Test
  public void everyStepIsToldWhereToAskForItsOwnRepositoryToBeReleased() {
    // The release train's maintenance step POSTs to qits-workspaces after the tests it follows went
    // green. Unconditional and container-dialled for the same reasons as the npm pair.
    for (LaunchSpec each : List.of(spec, publishing())) {
      assertEquals(
          "https://workspaces.qits.example.org", compose(each).env().get("QITS_WORKSPACES_URL"));
    }
  }

  @Test
  public void theBootstrapIsWhatTurnsACredentialIntoAFileAndItDoesItBeforeTheDaemonRuns() {
    // The document itself is composed from the run's token and asserted in StepEnvironmentTest; what is
    // pinned here is the mechanism, which is a property of BOOTSTRAP alone: two variables, a file
    // under /tmp — never in the checkout, so a `docker build` from /workspace can never carry the
    // credential into a published image — and written before the daemon becomes PID 1, or the step
    // would run before the file existed.
    String bootstrap = StepContainerSettings.BOOTSTRAP;
    assertTrue(bootstrap.contains("\"$DOCKER_CONFIG/config.json\""), bootstrap);
    assertTrue(bootstrap.contains("$QITS_CI_REGISTRY_AUTH_CONFIG"), bootstrap);
    assertTrue(
        StepContainerSettings.REGISTRY_AUTH_DIR.startsWith("/tmp/"),
        StepContainerSettings.REGISTRY_AUTH_DIR);
    assertTrue(
        bootstrap.indexOf("config.json") < bootstrap.indexOf("exec /tmp/qits-ci-daemon"), bootstrap);
  }

  @Test
  public void theBootstrapHandsTheTokenOverAndHoldsNoExchangeAtAll() {
    // The mechanism, as a property of BOOTSTRAP alone: an executable script under /tmp, chmod 0700,
    // an exported variable holding the token — under the token's guard, and before the daemon
    // becomes PID 1, or a step would run before either existed. What the files contain is
    // CiDaemonBootstrapTokenTest's subject.
    String bootstrap = StepContainerSettings.BOOTSTRAP;
    assertTrue(StepContainerSettings.PUBLISH_TOKEN_COMMAND.startsWith("/tmp/"),
        StepContainerSettings.PUBLISH_TOKEN_COMMAND);
    assertTrue(bootstrap.contains("cat > " + StepContainerSettings.PUBLISH_TOKEN_COMMAND), bootstrap);
    assertTrue(bootstrap.contains("chmod 0700 " + StepContainerSettings.PUBLISH_TOKEN_COMMAND), bootstrap);
    assertTrue(bootstrap.contains("QITS_PUBLISH_TOKEN=$QITS_TOKEN"), bootstrap);
    assertTrue(bootstrap.contains("export QITS_PUBLISH_TOKEN"), bootstrap);
    assertTrue(bootstrap.contains(StepContainerSettings.DEPLOY_SETTINGS_FILE), bootstrap);

    // ONE guard writes all of it, and ONE script: the token's.
    int guard = bootstrap.indexOf("if [ -n \"$QITS_TOKEN\" ]; then\n  cat > ");
    assertTrue(guard >= 0, bootstrap);
    assertEquals(
        1, countOf(bootstrap, "cat > " + StepContainerSettings.PUBLISH_TOKEN_COMMAND), bootstrap);
    assertEquals(1, countOf(bootstrap, "cat > /tmp/qits-git-credential"), bootstrap);
    assertTrue(
        bootstrap.indexOf("export QITS_PUBLISH_TOKEN")
            < bootstrap.indexOf("exec /tmp/qits-ci-daemon"),
        bootstrap);

    // qits-515: no client pair, no git-auth variables and no token exchange — nothing in this text
    // asks the idp for anything.
    for (String gone :
        List.of(
            "QITS_COMMISSIONED_CLIENT",
            "QITS_GIT_AUTH_",
            "grant_type",
            "access_token",
            "Authorization: Basic",
            "QITS_CI_DAEMON_SECRET")) {
      assertFalse(bootstrap.contains(gone), "the bootstrap still carries " + gone);
    }

    // And nothing prints the value. `set -x` is not on in this text either.
    assertFalse(bootstrap.contains("echo \"$QITS_PUBLISH_TOKEN\""), bootstrap);
    assertFalse(bootstrap.contains("echo \"$QITS_TOKEN\""), bootstrap);
    assertFalse(bootstrap.contains("set -x"), bootstrap);
  }

  private static int countOf(String text, String needle) {
    int count = 0;
    for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
      count++;
    }
    return count;
  }

  @Test
  public void aPlainStepIsExactlyTheContractAndADockerStepAddsItsBuildEnvironment() {
    // A plain step on somebody else's image: the contract and nothing else — no docker login, no
    // directory for one.
    Map<String, String> plainEnv = compose(spec).env();
    assertEquals(contractEnv(), plainEnv);
    assertFalse(plainEnv.containsKey("DOCKER_CONFIG"));
    assertFalse(plainEnv.containsKey("QITS_CI_REGISTRY_AUTH_CONFIG"));

    Map<String, String> publishingEnv = new LinkedHashMap<>(compose(publishing()).env());
    String document = publishingEnv.remove("QITS_CI_REGISTRY_AUTH_CONFIG");
    Map<String, String> expected = contractEnv();
    expected.put("DOCKER_BUILDKIT", "1");
    expected.put("BUILDX_NO_DEFAULT_ATTESTATIONS", "1");
    expected.put("QITS_BUILD_REGISTRY", "registry.qits.example.org");
    expected.put("DOCKER_CONFIG", "/tmp/qits-ci-registry-auth");
    assertEquals(expected, publishingEnv);
    assertTrue(document.contains("\"registry.qits.example.org\""), document);
    assertTrue(document.contains("\"mirror.qits.example.org\""), document);
  }

  @Test
  public void onlyADockerStepIsToldToUseBuildKit() {
    // Every step image ships buildx as of qits-oci 2026.814.110556, so a legacy build is a SILENT
    // FALLBACK rather than an image with no choice — and a silent fallback is what quietly drops a
    // --secret mount. DOCKER_BUILDKIT=1 makes it a loud error instead. The second variable keeps a
    // push a single manifest: buildx attaches provenance and SBOM attestations by default, and the
    // platform registry expects one manifest per tag.
    Map<String, String> publishingEnv = compose(publishing()).env();
    assertEquals("1", publishingEnv.get("DOCKER_BUILDKIT"));
    assertEquals("1", publishingEnv.get("BUILDX_NO_DEFAULT_ATTESTATIONS"));

    // And no step that cannot build gets an opinion about how builds are done.
    Map<String, String> plainEnv = compose(spec).env();
    assertFalse(plainEnv.containsKey("DOCKER_BUILDKIT"));
    assertFalse(plainEnv.containsKey("BUILDX_NO_DEFAULT_ATTESTATIONS"));
  }

  /**
   * <b>The run's own extras are written last and the platform's contract first.</b> Today the map is
   * the four {@code QITS_EVENT_*} of an event-triggered run and empty on every push, and none of it
   * is repo-authored — so this pins the ORDER rather than a safety property, exactly as the argv
   * did: last in the argv meant a repeated {@code --env} whose later value won, and last into a map
   * means the same thing.
   */
  @Test
  public void runScopedExtrasAreWrittenAfterTheContractAndInSortedOrder() {
    LaunchSpec triggered =
        new LaunchSpec(
            spec.runId(),
            spec.stepIndex(),
            spec.repo(),
            spec.branch(),
            spec.sha(),
            spec.image(),
            spec.daemonId(),
                spec.daemonBinaryUrl(),
            spec.stepTimeoutSeconds(),
            false,
            false,
            "",
            Map.of("QITS_EVENT_VERSION", "1.2.3", "QITS_EVENT_NAME", "SoftwareRelease"));

    Map<String, String> expected = contractEnv();
    expected.put("QITS_EVENT_NAME", "SoftwareRelease");
    expected.put("QITS_EVENT_VERSION", "1.2.3");

    Map<String, String> actual = compose(triggered).env();
    assertEquals(expected, actual);
    assertEquals(List.copyOf(expected.keySet()), List.copyOf(actual.keySet()), "written in this order");
  }

  // theRegistryLifetimeCoversEveryDeadlineAStepMaySpend used to prove the qits-containers EPHEMERAL
  // policy's maxAge summed every deadline a step could spend. maxAgeSeconds is deleted with the
  // in-process executor (qits-506): a runner's own boot sweep removes what a previous life of it
  // left behind, and there is no registry lifetime for qits-ci to compute any more.

  /**
   * <b>The container name is the ref, and one place per step of one run is what that buys.</b> A
   * retry of the same step has to address the same container rather than make a second one — which
   * is a property of the name being derived from the run and the step index and nothing else.
   */
  @Test
  public void theContainerNameIsAlsoTheRefAndIsStableForOneStep() {
    String name = StepContainerSettings.containerName(spec.runId(), spec.stepIndex());
    assertEquals(name, compose(spec).name());
    assertEquals(name, StepContainerSettings.containerName(spec.runId(), spec.stepIndex()));
    assertNotEquals(name, StepContainerSettings.containerName(spec.runId(), spec.stepIndex() + 1));
    // ContainersIdentifiers' charset for a ref: lowercase, alphanumerics and dashes, no leading one.
    assertTrue(name.matches("[a-z0-9][a-z0-9-]*"), name);
  }

  // theContainerIsNotSelfRemoving used to prove the qits-containers EPHEMERAL policy and
  // Recreate.never — both qits-containers vocabulary, deleted whole with the in-process executor
  // (qits-506). A runner's teardown is an explicit Reap for every step, never a self-removing
  // container; that is RunnerStepRunnerTest's claim now.

  @Test
  public void theBootstrapInterpolatesNothingAtAll() {
    String bootstrap = StepContainerSettings.BOOTSTRAP;
    // Every value the container needs is a shell variable it reads from its own environment. If any
    // of these appeared in the text, a repository would have found a way into a command line.
    for (String value :
        List.of(
            "repo-1",
            "cafebabe",
            "main",
            "daemon-7",
            StepFixtures.TOKEN,
            "maven:3.9",
            "qits-artifacts",
            "example.org")) {
      assertFalse(bootstrap.contains(value), "bootstrap must not carry '" + value + "'");
    }
    // ...and it travels as ITS OWN list element, which is what makes zero interpolation a property
    // of the construction rather than of an argv somebody has to keep reading.
    assertEquals(List.of("-c", bootstrap), compose(spec).args());
    assertEquals(List.of("/bin/sh"), compose(spec).entrypoint());
    // ...and the invariant the whole feature rests on: no repo-controlled code in a host argv.
    assertFalse(bootstrap.contains("bash -c"), bootstrap);
    // No docker vocabulary either — this text runs no program of that name and never has. It does
    // now write the push credential to $DOCKER_CONFIG, which is an environment variable the shell
    // expands rather than a command, and the assertion below still says so because the variable is
    // upper case and the program would not be.
    assertFalse(bootstrap.contains("docker"), bootstrap);
  }

  @Test
  public void theBootstrapProbesBothDownloadersAndSaysSoWhenItHasNeither() {
    String bootstrap = StepContainerSettings.BOOTSTRAP;
    assertTrue(bootstrap.contains("command -v wget"), bootstrap);
    assertTrue(bootstrap.contains("command -v curl"), bootstrap);
    // The image contract, stated in the container's own log — which is what the never-registered
    // teardown captures, so an image missing a downloader diagnoses itself.
    assertTrue(bootstrap.contains("neither wget nor curl"), bootstrap);
    assertTrue(bootstrap.contains("chmod +x /tmp/qits-ci-daemon"), bootstrap);
    // exec, so the daemon is PID 1 and the removal signals it rather than a wrapping shell.
    assertTrue(bootstrap.contains("exec /tmp/qits-ci-daemon"), bootstrap);
  }

  @Test
  public void theBinaryIsPinnedAsThePathOfItsVersionInTheDaemonsStore() {
    // A path and no origin: the pin is taken once per run and needs no domain to be taken. The
    // origin is the registry's public name, added when a step is composed — contractEnv's
    // QITS_CI_DAEMON_BINARY_URL is this path under it.
    assertEquals(
        "/artifacts/daemons/qits-ci-daemon/abc123", launcher().resolveBinaryUrl("abc123"));
  }

  /**
   * The base names the SERVICE and ci appends {@code /git/<repoId>}; a trailing slash on the base is
   * stripped either way.
   */
  @Test
  public void theCloneUrlEndsAtTheServiceAndCiAppendsTheGitSegment() {
    assertEquals(
        "https://githost.qits.example.org/git/repo-1",
        StepWorkloadSpecs.cloneUrl("https://githost.qits.example.org/", CiRepoRef.of("repo-1")));
    assertEquals(
        "http://a-host-of-any-depth/below/git/repo-1",
        StepWorkloadSpecs.cloneUrl("http://a-host-of-any-depth/below", CiRepoRef.of("repo-1")));
    // And the public form, which is what a named run clones from: the project and the name, never
    // the storage id — after the cutover that id is not an address a step container may use at all.
    assertEquals(
        "https://githost.qits.example.org/git/qits/qits-blobstore",
        StepWorkloadSpecs.cloneUrl(
            "https://githost.qits.example.org",
            CiRepoRef.of("2f1c9b3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f", "qits", "qits-blobstore")));
  }

  // hostileIdentifiersAreRejectedBeforeAnyCallIsMade used to prove StepContainerSettings#launch's
  // own pre-flight validation. `launch` is deleted with the in-process executor (qits-506): the
  // identifier validation it did (CiIdentifiers.requireRepo/requireBranch/requireSha/requireImage)
  // now runs at the very top of RunnerStepRunner#run, before a secret is minted or a relay opened —
  // see that class's own suite for the equivalent claim.

  @Test
  public void shortRunIdsStillNameAValidStableContainer() {
    // "Used whole" no longer literally holds -- a disambiguator now rides alongside even a runId
    // short enough to need no truncation, because containerName must not assume any runId shape.
    // What still holds: the hint stays readable, and the same input always names the same container.
    String name = StepContainerSettings.containerName("abc", 0);
    assertEquals("qits-ci-abc-17862-0", name);
    assertEquals(name, StepContainerSettings.containerName("abc", 0), "must be deterministic");
    assertTrue(name.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]*"), "must stay inside docker's name charset");
  }

  @Test
  public void twoRunIdsSharingAnEightCharacterPrefixNeverCollide() {
    // The literal shape of today's incident: every probe runId used to be "daemon-probe-" + a UUID,
    // so the blind 8-character substring was always "daemon-p" and two concurrent probes always
    // named the same container. Both runIds below still share that same 8-character prefix; the
    // disambiguator -- derived from the WHOLE runId -- is what keeps their container names apart now.
    String a = StepContainerSettings.containerName("daemon-probe-11111111-1111-1111-1111-111111111111", 0);
    String b = StepContainerSettings.containerName("daemon-probe-22222222-2222-2222-2222-222222222222", 0);
    assertTrue(a.startsWith("qits-ci-daemon-p-"), a);
    assertTrue(b.startsWith("qits-ci-daemon-p-"), b);
    assertFalse(a.equals(b), "runIds sharing an 8-char prefix must still name different containers");
  }

  @Test
  public void twoFreshRunIdsNeverCollide() {
    // The concrete case this incident hit -- it was the pin ladder's probe, which minted a bare UUID
    // per candidate, and the property is the launcher's rather than the probe's so it outlives it:
    // two distinct random UUIDs must not collide on the resulting container name. Not a guarantee
    // about UUID collisions in general -- just that containerName does not throw the entropy away
    // the way the old blind prefix did.
    String runIdA = java.util.UUID.randomUUID().toString();
    String runIdB = java.util.UUID.randomUUID().toString();
    assertFalse(runIdA.equals(runIdB), "test setup: the two random UUIDs must differ");
    assertFalse(
        StepContainerSettings.containerName(runIdA, 0)
            .equals(StepContainerSettings.containerName(runIdB, 0)),
        "two distinct runIds must not collide on the container name");
  }

  // The docker-is-down WARN that used to live here went with the CLI it was about: there is no
  // `docker ps` to exit non-zero any more. Its successor is the runner's own boot sweep, which is
  // RunnerStepRunnerTest's territory now, not this class's.

  // The boot-time shape check that used to live here (daemonVersionComplaint) is gone with the
  // template flip: it warned only while the shipped template still addressed the binary by digest,
  // and it would have gone silent by construction the moment that stopped being true. Its
  // replacement, CiIdentifiers.requireDaemonVersion, was enforced where a version really did arrive
  // untrusted — at adoption, off a SoftwareRelease frame. There is no adoption any more: the version
  // is a constant compiled into the protocol jar, or an override typed into this deployment's own
  // configuration, and neither is attacker-shaped. The check is kept unused rather than deleted, for
  // the reason its own javadoc gives.
}
