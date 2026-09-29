package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The composer, against <b>golden documents</b>.
 *
 * <p>That is the point of this file rather than an implementation detail of it. The composed
 * document is what lands in {@code ci_run.trigger_config} and is what every step of every release in
 * the estate will really run; a change to the prelude is a change to 47 repositories' behaviour made
 * in one place, and the only way that stays reviewable is if the diff is <b>the text itself</b>. So
 * the expectations are files under {@code src/test/resources/composed/}, and a change to this class
 * shows up as a diff a person can read line by line.
 *
 * <p><b>Regenerating is deliberate work, not a flag.</b> A missing golden writes the composed text
 * to {@code target/composed/} and fails naming both paths; you read the produced file, satisfy
 * yourself that the change is the one you meant, and copy it in. An {@code -Dupdate.goldens} switch
 * would make "the test agrees with itself" the default outcome, which is the one thing a golden must
 * never be able to do.
 *
 * <p>Plain JUnit and no fakes: {@link CiReleaseComposer} is a pure function of three arguments.
 */
public class CiReleaseComposerTest {

  private final CiReleaseSlotParser parser = new CiReleaseSlotParser();

  private static final CiRepoRef REPO =
      CiRepoRef.of("2f1c9b3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f", "qits", "qits-ci-service");

  private CiReleaseSlots slots(String content) {
    return parser.parse(CiReleaseSlotParser.CONFIG_PATH, content);
  }

  private CiReleaseSlots archetype(String name, String content) {
    return parser.parseArchetype(CiReleaseSlotParser.archetypePath(name), content);
  }

  // --- the three shapes the fleet is made of ------------------------------------------------------

  /** 15 repositories: the whole file is one line and the archetype is the pipeline. */
  private static final String SPA_FRONTEND =
      """
      release-request:
        - image: qits/build-images/node-base:latest
          timeout-seconds: 1800
          script: |
            npm ci --no-audit --no-fund
            npm run lint
            npm run test
            npm run build
      """;

  /** 19 repositories: a QA fold and a release publish, both building through the platform builder. */
  private static final String JAVA_SERVICE =
      """
      release-request:
        - image: qits/build-images/ci-base:latest
          build: true
          timeout-seconds: 3600
          script: |
            buildctl build --frontend dockerfile.v0 --local context=. --local dockerfile=docker
      release:
        - image: qits/build-images/ci-base:latest
          build: true
          timeout-seconds: 3600
          script: |
            buildctl build --frontend dockerfile.v0 \\
              --local context=. --local dockerfile=docker \\
              --output "type=image,name=$QITS_BUILD_REGISTRY/$QITS_IMAGE_REPOSITORY/qits-ci:$QITS_VERSION,push=true"
            buildctl build --frontend dockerfile.v0 --opt target=sbom \\
              --local context=. --local dockerfile=docker --output type=local,dest=out
      """;

  @Test
  public void anArchetypeAloneComposesTheQaPipelineAndNoRelease() {
    CiReleaseComposer.Composed composed =
        CiReleaseComposer.compose(
            CiRepoRef.of("11111111-2222-3333-4444-555555555555", "qits", "qits-observability-frontend"),
            slots("archetype: spa-frontend\n"),
            archetype("spa-frontend", SPA_FRONTEND));

    golden("spa-frontend-release-request.yml", composed.releaseRequestDocument());
    // AND NO RELEASE DOCUMENT. An SPA frontend publishes nothing — it is built into the consuming
    // service's image — so it has no ci-event-release.yml today, and "no steps declared for a phase"
    // must mean "no run" rather than a trivially green one.
    assertNull(composed.releaseDocument());
  }

  @Test
  public void aRepositorySlotReplacesTheArchetypesEntirely() {
    CiReleaseComposer.Composed composed =
        CiReleaseComposer.compose(
            REPO,
            slots(
                """
                archetype: java-service
                release-request:
                  - image: qits/build-images/ci-base:latest
                    build: true
                    script: |
                      git submodule update --init
                      ./mvnw -q verify
                  - image: qits/build-images/maven-base:latest
                    script: ./mvnw -q verify -Dit.test=BuildTriggerIT
                artifacts:
                  - { type: docker, name: qits/qits-ci, sbom: out/sbom.json }
                userflows: true
                """),
            archetype("java-service", JAVA_SERVICE));

    // WHOLE SLOT, never per-step: the repository's two QA steps are the pipeline, and the
    // archetype's QA step is not merged in anywhere. The release slot it did NOT declare is the
    // archetype's, unchanged.
    golden("java-service-override-release-request.yml", composed.releaseRequestDocument());
    golden("java-service-override-release.yml", composed.releaseDocument());
  }

  @Test
  public void aBespokeRepositoryComposesFromItsOwnSlotsAlone() {
    CiReleaseComposer.Composed composed =
        CiReleaseComposer.compose(
            CiRepoRef.of("99999999-8888-7777-6666-555555555555", "qits", "qits-ci-daemon"),
            slots(
                """
                release-request:
                  - image: qits/build-images/ci-base:latest
                    build: true
                    script: buildctl build --frontend dockerfile.v0 --opt target=binary
                release:
                  - image: qits/build-images/ci-base:latest
                    build: true
                    timeout-seconds: 3600
                    script: |
                      buildctl build --frontend dockerfile.v0 --opt target=binary --output type=local,dest=out
                      qits-publish daemon submit --name qits-ci-daemon --version "$QITS_VERSION" --file out/qits-ci-daemon
                artifacts:
                  - { type: daemon, name: qits-ci-daemon, sbom: out/sbom.json }
                """),
            null);

    golden("bespoke-release-request.yml", composed.releaseRequestDocument());
    golden("bespoke-release.yml", composed.releaseDocument());
  }

  /**
   * The one repository that cannot run on a platform step image: it BUILDS them.
   *
   * <p>qits-build-images-oci would bootstrap itself on {@code qits/build-images/*}, so it runs on
   * upstream {@code docker:28-dind} — {@code /bin/sh} and {@code /bin/ash}, no {@code /bin/bash};
   * {@code wget}, no {@code curl}. Composed against that image the wrapper used to die at {@code
   * bash: not found} before building anything, in the one repository whose bad release breaks every
   * other pipeline's step images.
   */
  @Test
  public void anImageWithoutBashRunsTheDeclaredScriptUnderSh() {
    CiReleaseComposer.Composed composed =
        CiReleaseComposer.compose(
            CiRepoRef.of("77777777-6666-5555-4444-333333333333", "qits", "qits-build-images-oci"),
            slots(
                """
                release:
                  - image: docker:28-dind
                    docker: true
                    timeout-seconds: 3600
                    script: |
                      docker build -t "$QITS_BUILD_REGISTRY/qits/build-images/ci-base:$QITS_VERSION" ci-base
                      docker push "$QITS_BUILD_REGISTRY/qits/build-images/ci-base:$QITS_VERSION"
                artifacts:
                  - { type: docker, name: qits/build-images/ci-base, sbom: out/sbom.json }
                """),
            null);

    golden("no-bash-image-release.yml", composed.releaseDocument());

    // The two run-time checks, as behaviour rather than as text: nothing in the composed step may
    // reach bash or curl without having asked for them first, on ANY image — the composer cannot
    // see inside one, so there is no per-image arm here to get wrong.
    String document = composed.releaseDocument();
    assertTrue(
        document.contains("if command -v bash > /dev/null 2>&1; then")
            && document.contains("  sh -eu /tmp/qits-slot.sh"),
        document);
    assertTrue(
        document.contains("elif command -v wget > /dev/null 2>&1; then")
            && document.contains("wget -q \"$@\" -O /tmp/qits-bin/qits"),
        document);
    // And the refusal names the image, so an author reads a sentence about their own file rather
    // than `curl: not found` out of a line they never wrote.
    assertTrue(
        document.contains("the image for this step (docker:28-dind) has neither curl nor wget"),
        document);
    // Each named program is reached exactly once and only from inside its own test, so neither can
    // become an unguarded line again while the assertions above still pass.
    assertEquals(1, occurrences(document, "bash -eu"), document);
    assertEquals(1, occurrences(document, "curl -fsSL"), document);
    assertTrue(
        document.indexOf("command -v bash") < document.indexOf("bash -eu")
            && document.indexOf("command -v curl") < document.indexOf("curl -fsSL"),
        document);
  }

  @Test
  public void aBashImageStillRunsTheSameInvocationsItRanBefore() {
    // THE OTHER HALF, and the reason the fallback is a run-time check rather than a compose-time
    // one: for the 48 repositories whose images do have bash, the chosen arm is byte-identical to
    // what the composer emitted before the fallback existed. The goldens hold the whole documents;
    // this pins the two lines that a careless "portability" change would rewrite.
    CiReleaseComposer.Composed composed =
        CiReleaseComposer.compose(
            REPO, slots("archetype: java-service\n"), archetype("java-service", JAVA_SERVICE));

    assertTrue(composed.releaseRequestDocument().contains("  bash -eu /tmp/qits-slot.sh\n"));
    assertTrue(
        composed
            .releaseDocument()
            .contains(
                "curl -fsSL --retry 2 --retry-delay 2 \"$@\" -o /tmp/qits-bin/qits"
                    + " \"$QITS_ARTIFACTS_URL/artifacts/daemons/$QITS_ARTIFACTS_CLI_PACKAGE/$QITS_ARTIFACTS_CLI_VERSION\"\n"),
        composed.releaseDocument());
  }

  @Test
  public void theCliDownloadCarriesTheEdgeBearerOnBothArms() {
    // epic qits-441: a release-phase step on the EDGE plane authenticates with this run's ci-run
    // token, $QITS_TOKEN — the CLI download is anonymous otherwise and a 401 in 0s through the
    // public edge. The composer emits no per-deployment branch for this; the choice is made at
    // RUN TIME by the same `set --`/`"$@"` idiom CiDaemonLauncher.BOOTSTRAP already uses for the
    // ci-daemon binary's own download, so a token-less internal run is byte-identical to before.
    CiReleaseComposer.Composed composed =
        CiReleaseComposer.compose(
            REPO, slots("archetype: java-service\n"), archetype("java-service", JAVA_SERVICE));

    String document = composed.releaseDocument();
    assertTrue(
        document.contains(
            "        set --\n"
                + "        if [ -n \"${QITS_TOKEN:-}\" ]; then\n"
                + "          set -- --header \"Authorization: Bearer $QITS_TOKEN\"\n"
                + "        fi\n"),
        document);
    assertTrue(
        document.contains(
            "curl -fsSL --retry 2 --retry-delay 2 \"$@\" -o /tmp/qits-bin/qits"
                + " \"$QITS_ARTIFACTS_URL/artifacts/daemons/$QITS_ARTIFACTS_CLI_PACKAGE/$QITS_ARTIFACTS_CLI_VERSION\"\n"),
        document);
    assertTrue(
        document.contains(
            "wget -q \"$@\" -O /tmp/qits-bin/qits"
                + " \"$QITS_ARTIFACTS_URL/artifacts/daemons/$QITS_ARTIFACTS_CLI_PACKAGE/$QITS_ARTIFACTS_CLI_VERSION\"\n"),
        document);
    // The `set --` block sits ONCE, before both arms, rather than once per arm — a second copy
    // would be a second place for the two to drift.
    assertEquals(1, occurrences(document, "set --\n"));
  }

  @Test
  public void theDockerBuildSecretFilesFallBackToTheRunToken() {
    // The second instance of the same defect (epic qits-441): the commissioned pair is what a
    // buildctl `--secret id=…` mount reads for the maven mirror's credential, and on the EDGE
    // plane a step holds no pair — only $QITS_TOKEN. Both files used to come out empty in that
    // case (`printf '%s' "${QITS_COMMISSIONED_CLIENT_ID:-}"` with nothing to substitute), which
    // is an empty Basic credential and a 401 from the mirror rather than an anonymous read. The
    // pair still wins when both halves are present; only their absence falls back to the token.
    CiReleaseComposer.Composed composed =
        CiReleaseComposer.compose(
            REPO, slots("archetype: java-service\n"), archetype("java-service", JAVA_SERVICE));

    String document = composed.releaseDocument();
    assertTrue(
        document.contains(
            "      (\n"
                + "        umask 077\n"
                + "        if [ -n \"${QITS_COMMISSIONED_CLIENT_ID:-}\" ]; then\n"
                + "          printf '%s' \"$QITS_COMMISSIONED_CLIENT_ID\" > /tmp/qits-client-id\n"
                + "          printf '%s' \"$QITS_COMMISSIONED_CLIENT_SECRET\" >"
                + " /tmp/qits-client-secret\n"
                + "        elif [ -n \"${QITS_TOKEN:-}\" ]; then\n"
                + "          printf '%s' \"${QITS_TOKEN_SUBJECT:-qits-ci-run}\" >"
                + " /tmp/qits-client-id\n"
                + "          printf '%s' \"$QITS_TOKEN\" > /tmp/qits-client-secret\n"
                + "        else\n"
                + "          printf '' > /tmp/qits-client-id\n"
                + "          printf '' > /tmp/qits-client-secret\n"
                + "        fi\n"
                + "      )\n"),
        document);
  }

  @Test
  public void theDockerBuildSecretFilesReachARealShellCorrectlyInAllThreeArms() throws Exception {
    // Same shape as the CLI-download execution test below: the string assertion above proves
    // what bytes are emitted, this proves a shell reads them the way the comment claims, across
    // the pair, the token-only fallback, and neither.
    CiReleaseComposer.Composed composed =
        CiReleaseComposer.compose(
            REPO, slots("archetype: java-service\n"), archetype("java-service", JAVA_SERVICE));
    String fragment = extractSecretFilesFragment(composed.releaseDocument());

    Path work = Files.createTempDirectory("docker-secret-fragment");
    try {
      Path scriptFile = work.resolve("run.sh");
      Files.writeString(scriptFile, fragment);

      // The pair present: it wins even though a token is also set.
      run(scriptFile, work, Map.of(
          "QITS_COMMISSIONED_CLIENT_ID", "the-client-id",
          "QITS_COMMISSIONED_CLIENT_SECRET", "the-client-secret",
          "QITS_TOKEN", "the-run-token",
          "QITS_TOKEN_SUBJECT", "the-run-subject"));
      assertEquals("the-client-id", Files.readString(work.resolve("qits-client-id")));
      assertEquals("the-client-secret", Files.readString(work.resolve("qits-client-secret")));

      // No pair, a token: the fallback.
      run(scriptFile, work, Map.of(
          "QITS_TOKEN", "the-run-token",
          "QITS_TOKEN_SUBJECT", "the-run-subject"));
      assertEquals("the-run-subject", Files.readString(work.resolve("qits-client-id")));
      assertEquals("the-run-token", Files.readString(work.resolve("qits-client-secret")));

      // No pair, a token, and no subject — an older qits-ci: still a real id rather than an
      // empty one.
      run(scriptFile, work, Map.of("QITS_TOKEN", "the-run-token"));
      assertEquals("qits-ci-run", Files.readString(work.resolve("qits-client-id")));
      assertEquals("the-run-token", Files.readString(work.resolve("qits-client-secret")));

      // Neither: the internal-plane case, unchanged — both files empty rather than the script
      // failing under -eu on an unset variable.
      run(scriptFile, work, Map.of());
      assertEquals("", Files.readString(work.resolve("qits-client-id")));
      assertEquals("", Files.readString(work.resolve("qits-client-secret")));
    } finally {
      try (var stream = Files.walk(work)) {
        stream
            .sorted(java.util.Comparator.reverseOrder())
            .forEach(
                path -> {
                  try {
                    Files.deleteIfExists(path);
                  } catch (IOException ignored) {
                    // best-effort cleanup
                  }
                });
      }
    }
  }

  private static void run(Path scriptFile, Path work, Map<String, String> env) throws Exception {
    Files.deleteIfExists(work.resolve("qits-client-id"));
    Files.deleteIfExists(work.resolve("qits-client-secret"));
    ProcessBuilder pb =
        new ProcessBuilder("/bin/sh", "-eu", scriptFile.toString())
            .directory(work.toFile())
            .redirectErrorStream(true);
    pb.environment().clear();
    pb.environment().put("PATH", System.getenv("PATH"));
    pb.environment().putAll(env);
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    int exit = p.waitFor();
    assertEquals(0, exit, "script exited " + exit + ": " + out);
  }

  /**
   * Cuts the {@code (} … {@code )} docker-build-secret fragment out of a composed document's step
   * script and rewrites the two output files to land beside the script rather than at
   * {@code /tmp}, so the test can read them back without root or a shared {@code /tmp} state.
   */
  private static String extractSecretFilesFragment(String document) {
    int begin = document.indexOf("      (\n        umask 077\n");
    int end = document.indexOf("      )\n", begin) + "      )\n".length();
    assertTrue(begin >= 0 && end > begin, document);
    String indented = document.substring(begin, end);
    StringBuilder out = new StringBuilder();
    for (String line : indented.split("\n", -1)) {
      out.append(line.length() >= 6 ? line.substring(6) : line).append('\n');
    }
    return "set -eu\n"
        + out.toString()
            .replace("/tmp/qits-client-id", "qits-client-id")
            .replace("/tmp/qits-client-secret", "qits-client-secret");
  }

  @Test
  public void theCliDownloadReachesARealCurlWithTheBearerHeader() throws Exception {
    // The composed text is never executed by this suite anywhere else — the goldens and the
    // string assertions above prove what bytes are emitted, not that a shell reads them the way
    // this test means. This drives the actual CLI-download fragment under `sh -eu` with a stub
    // `curl` on PATH that records its own argv, once with $QITS_TOKEN set and once without, and
    // asserts the header reaches curl in exactly the case it must.
    // A non-build, non-docker step, deliberately: it composes with neither the BUILDKIT_HOST guard
    // nor the commissioned-secret subshell in front of the CLI download, so the extracted fragment
    // below is exactly the download and nothing this test would otherwise have to stage platform
    // config for.
    CiReleaseComposer.Composed composed =
        CiReleaseComposer.compose(
            REPO,
            slots(
                """
                release:
                  - image: alpine:3
                    script: echo hi
                artifacts:
                  - { type: docker, name: qits/thing, sbom: out/sbom.json }
                """),
            null);
    String document = composed.releaseDocument();
    String script = extractStepScript(document);

    Path work = Files.createTempDirectory("cli-download-fragment");
    try {
      Path bin = work.resolve("bin");
      Files.createDirectories(bin);
      Path stubCurl = bin.resolve("curl");
      Files.writeString(
          stubCurl,
          "#!/bin/sh\n"
              + "printf '%s\\n' \"$*\" >> \""
              + work.resolve("curl-argv.txt")
              + "\"\n"
              // Find the file named after -o and create it, so the caller's `chmod +x` on it
              // does not fail the script under -eu.
              + "prev=\n"
              + "for a in \"$@\"; do\n"
              + "  if [ \"$prev\" = \"-o\" ]; then\n"
              + "    touch \"$a\"\n"
              + "  fi\n"
              + "  prev=\"$a\"\n"
              + "done\n"
              + "exit 0\n");
      stubCurl.toFile().setExecutable(true);

      // The extracted fragment still opens with the release phase's own git fetch/checkout —
      // extractStepScript only rewrites the CLI-package guard, deliberately, so the fragment stays
      // the composer's real text. A stub `git` that no-ops keeps that preamble truthful without
      // this test standing up a real repository.
      Path stubGit = bin.resolve("git");
      Files.writeString(stubGit, "#!/bin/sh\nexit 0\n");
      stubGit.toFile().setExecutable(true);

      Path scriptFile = work.resolve("run.sh");
      Files.writeString(scriptFile, script);

      // Run WITH a token.
      ProcessBuilder withToken =
          new ProcessBuilder("/bin/sh", "-eu", scriptFile.toString())
              .directory(work.toFile())
              .redirectErrorStream(true);
      withToken.environment().clear();
      // The stub curl has to win over any real one, so it leads; the rest of the real PATH stays
      // so mkdir/chmod/command — ordinary external programs this fragment also runs — resolve.
      withToken.environment().put("PATH", bin + ":" + System.getenv("PATH"));
      withToken.environment().put("QITS_TOKEN", "the-run-token");
      withToken.environment().put("QITS_ARTIFACTS_CLI_PACKAGE", "");
      withToken.environment().put("QITS_VERSION", "2026.929.1");
      withToken.environment().put("QITS_CI_REPOSITORY_URL", "http://githost.invalid/x");
      Process p1 = withToken.start();
      String out1 = new String(p1.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      p1.waitFor();

      String argvWithToken = Files.readString(work.resolve("curl-argv.txt"));
      assertTrue(
          argvWithToken.contains("--header Authorization: Bearer the-run-token"),
          "expected the bearer header in curl's argv, got: " + argvWithToken + " / stdout: " + out1);

      // Run again with NO token: the internal-plane case must be unchanged.
      Files.deleteIfExists(work.resolve("curl-argv.txt"));
      ProcessBuilder noToken =
          new ProcessBuilder("/bin/sh", "-eu", scriptFile.toString())
              .directory(work.toFile())
              .redirectErrorStream(true);
      noToken.environment().clear();
      noToken.environment().put("PATH", bin + ":" + System.getenv("PATH"));
      noToken.environment().put("QITS_ARTIFACTS_CLI_PACKAGE", "");
      noToken.environment().put("QITS_VERSION", "2026.929.1");
      noToken.environment().put("QITS_CI_REPOSITORY_URL", "http://githost.invalid/x");
      Process p2 = noToken.start();
      String out2 = new String(p2.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      p2.waitFor();

      String argvNoToken = Files.readString(work.resolve("curl-argv.txt"));
      assertTrue(
          !argvNoToken.contains("Authorization"),
          "expected no bearer header with no token, got: " + argvNoToken + " / stdout: " + out2);
    } finally {
      try (var stream = Files.walk(work)) {
        stream
            .sorted(java.util.Comparator.reverseOrder())
            .forEach(
                path -> {
                  try {
                    Files.deleteIfExists(path);
                  } catch (IOException ignored) {
                    // best-effort cleanup
                  }
                });
      }
    }
  }

  /**
   * Cuts the {@code set -eu} … {@code fi} CLI-download fragment out of a composed document's
   * step script, dropping the qits-artifacts-cli-package guard so a test with the package unset
   * still exercises the fetch itself (this class's fixtures never inject
   * {@code QITS_ARTIFACTS_CLI_PACKAGE}, and the real guard would just skip the block).
   */
  private static String extractStepScript(String document) {
    int begin = document.indexOf("      set -eu\n");
    int end = document.indexOf("# --- the declared step");
    assertTrue(begin >= 0 && end > begin, document);
    String indented = document.substring(begin, end);
    StringBuilder out = new StringBuilder();
    for (String line : indented.split("\n", -1)) {
      // Strip the YAML block-scalar indent (6 spaces) so the fragment is a plain shell script.
      out.append(line.length() >= 6 ? line.substring(6) : line).append('\n');
    }
    // Force the fetch branch open regardless of whether the package var is set, and replace the
    // guard's hard failure on a missing version with a harmless local default so the fragment
    // runs to completion under a stub curl with no other platform config supplied.
    return out.toString()
        .replace(
            "if [ -n \"${QITS_ARTIFACTS_CLI_PACKAGE:-}\" ]; then",
            "QITS_ARTIFACTS_CLI_PACKAGE=qits\nQITS_ARTIFACTS_CLI_VERSION=1\n"
                + "QITS_ARTIFACTS_URL=http://artifacts.invalid\nif true; then");
  }

  // --- the properties the goldens are there to hold ------------------------------------------------

  @Test
  public void everyComposedDocumentParsesAsAnOrdinaryTriggerFile() {
    // THE PROPERTY THE WHOLE DESIGN RESTS ON. A composed document is not a new file kind: it is the
    // existing trigger schema, so restart-reparse (which reads config_path + trigger_config off the
    // row) works on it with no code that knows it was composed.
    CiEventTriggerParser triggers = new CiEventTriggerParser();
    CiReleaseComposer.Composed composed =
        CiReleaseComposer.compose(
            REPO,
            slots(
                """
                archetype: java-service
                artifacts:
                  - { type: docker, name: qits/qits-ci, sbom: out/sbom.json }
                """),
            archetype("java-service", JAVA_SERVICE));

    CiEventTrigger qa =
        triggers.parse(CiReleaseSlotParser.CONFIG_PATH, composed.releaseRequestDocument());
    assertEquals(CiReleaseComposer.RELEASE_REQUEST_EVENT, qa.eventName());
    assertEquals(CiReleaseSlotParser.CONFIG_PATH, qa.configPath());
    assertEquals("backingBranch", qa.checkout().branchPath());
    assertEquals("mergedSha", qa.checkout().shaPath());
    assertEquals(false, qa.checkout().optional(), "a request naming no fold has nothing to gate");
    assertTrue(qa.artifacts().isEmpty(), "a fold publishes nothing");

    CiEventTrigger release =
        triggers.parse(CiReleaseSlotParser.CONFIG_PATH, composed.releaseDocument());
    assertEquals(CiReleaseComposer.RELEASE_EVENT, release.eventName());
    assertEquals("version", release.checkout().branchPath());
    assertEquals("commitSha", release.checkout().shaPath());
    assertEquals(
        false,
        release.checkout().optional(),
        "no optional: on a composed release either — the arm it opened dispatched a RELEASE run at"
            + " main's head with its checkout stripped, and the compatibility it advertised (an"
            + " SCMRelease with no commitSha) composes no document at all now, so the flag guarded"
            + " nothing but the defect");
    assertEquals(1, release.artifacts().size());
    assertEquals(CiArtifact.Type.DOCKER, release.artifacts().get(0).type());
    assertEquals("qits/qits-ci", release.artifacts().get(0).name());

    // And the round trip is byte-exact through the block scalar, which is the half a schema check
    // cannot see: a script that came back one space short would still parse and would run wrong.
    assertEquals(1, release.pipeline().steps().size());
    assertTrue(release.pipeline().steps().get(0).script().startsWith("set -eu\n"));
    assertTrue(
        release.pipeline().steps().get(0).script().contains("--output \"type=image,name="),
        "the declared script survives the heredoc and the block scalar verbatim");
  }

  @Test
  public void aScriptCarryingTheHeredocDelimiterIsRefused() {
    // The one way out of a quoted heredoc, refused at composition and named after the file a person
    // edits — never a wrapper that ends halfway through and runs the rest as shell.
    CiConfigException refused =
        assertThrows(
            CiConfigException.class,
            () ->
                CiReleaseComposer.compose(
                    REPO,
                    slots(
                        "release-request:\n"
                            + "  - image: alpine:3\n"
                            + "    script: |\n"
                            + "      echo one\n"
                            + "      "
                            + CiReleaseComposer.HEREDOC_DELIMITER
                            + "\n"
                            + "      rm -rf /\n"),
                    null));

    assertTrue(refused.getMessage().startsWith(CiReleaseSlotParser.CONFIG_PATH), refused.getMessage());
    assertTrue(refused.getMessage().contains(CiReleaseComposer.HEREDOC_DELIMITER));
  }

  @Test
  public void theRefusalNamesTheArchetypeWhenTheScriptIsTheArchetypes() {
    CiConfigException refused =
        assertThrows(
            CiConfigException.class,
            () ->
                CiReleaseComposer.compose(
                    REPO,
                    slots("archetype: broken\n"),
                    archetype(
                        "broken",
                        "release-request:\n  - image: alpine:3\n    script: echo "
                            + CiReleaseComposer.HEREDOC_DELIMITER
                            + "\n")));

    // A repository must never be told to fix a file it does not own.
    assertTrue(
        refused.getMessage().startsWith(".config/qits/release-archetypes/broken.yml"),
        refused.getMessage());
  }

  @Test
  public void artifactsWithNoReleasePipelineAreRefused() {
    // A declaration is a claim, and a claim with no pipeline behind it announces a SoftwareRelease
    // for bytes nothing published.
    CiConfigException refused =
        assertThrows(
            CiConfigException.class,
            () ->
                CiReleaseComposer.compose(
                    REPO,
                    slots(
                        "release-request:\n  - {image: alpine:3, script: echo qa}\n"
                            + "artifacts:\n  - { type: docker, name: qits/qits-ci }\n"),
                    null));

    assertTrue(refused.getMessage().contains("no pipeline behind it"), refused.getMessage());
  }

  @Test
  public void anIdAddressedCandidateSelectsOnItsStorageId() {
    // The compatibility arm every address in this engine carries. Pre-cutover the id IS the name;
    // post-cutover a candidate with no name is one nothing can address anyway.
    CiReleaseComposer.Composed composed =
        CiReleaseComposer.compose(
            CiRepoRef.of("qits-legacy-repo"),
            slots("release-request:\n  - {image: alpine:3, script: echo qa}\n"),
            null);

    assertTrue(
        composed.releaseRequestDocument().contains("- repoName: { exact: 'qits-legacy-repo' }"),
        composed.releaseRequestDocument());
  }

  @Test
  public void theSbomPostludeGoesOnTheLastBuildingStep() {
    CiReleaseComposer.Composed composed =
        CiReleaseComposer.compose(
            REPO,
            slots(
                """
                release:
                  - image: qits/build-images/ci-base:latest
                    build: true
                    script: buildctl build --opt target=binary
                  - image: qits/build-images/node-base:latest
                    script: npm run docs
                artifacts:
                  - { type: docker, name: qits/qits-ci, sbom: out/sbom.json }
                """),
            null);

    // The document is produced by the build, so it is submitted from the step that produced it —
    // and BEFORE that step's exit code, which is what makes "SBOM before green" structural.
    String document = composed.releaseDocument();
    int build = document.indexOf("buildctl build --opt target=binary");
    int submit = document.indexOf("qits artifacts publish sbom submit");
    int docs = document.indexOf("npm run docs");
    assertTrue(build > 0 && submit > build && docs > submit, document);
  }

  // --- goldens -------------------------------------------------------------------------------------

  private static int occurrences(String text, String needle) {
    int count = 0;
    for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
      count++;
    }
    return count;
  }

  private void golden(String name, String actual) {
    assertNotNull(actual, "nothing was composed for " + name);
    String expected = read("composed/" + name);
    if (expected == null) {
      Path written = write(name, actual);
      fail(
          "No golden for "
              + name
              + ". The composed document was written to "
              + written
              + " — read it, satisfy yourself the change is the one you meant, and copy it to"
              + " ci/src/test/resources/composed/"
              + name);
      return;
    }
    assertEquals(expected, actual, name);
  }

  private static String read(String resource) {
    try (InputStream in =
        CiReleaseComposerTest.class.getClassLoader().getResourceAsStream(resource)) {
      return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("could not read " + resource, e);
    }
  }

  private static Path write(String name, String content) {
    try {
      Path dir = Path.of("target", "composed");
      Files.createDirectories(dir);
      Path file = dir.resolve(name);
      Files.writeString(file, content, StandardCharsets.UTF_8);
      return file.toAbsolutePath();
    } catch (IOException e) {
      throw new IllegalStateException("could not write the composed document for " + name, e);
    }
  }
}
