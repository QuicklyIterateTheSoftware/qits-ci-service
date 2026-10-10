package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

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
              --output "type=image,name=registry.qits.$QITS_DOMAIN/$QITS_IMAGE_REPOSITORY/qits-ci:$QITS_VERSION,push=true"
            buildctl build --frontend dockerfile.v0 --opt target=sbom \\
              --local context=. --local dockerfile=docker --output type=local,dest=out
      """;

  @Test
  public void anArchetypeAloneComposesTheQaPipelineAndAChangelogOnlyRelease() {
    CiReleaseComposer.Composed composed =
        CiReleaseComposer.compose(
            CiRepoRef.of("11111111-2222-3333-4444-555555555555", "qits", "qits-observability-frontend"),
            slots("archetype: spa-frontend\n"),
            archetype("spa-frontend", SPA_FRONTEND));

    golden("spa-frontend-release-request.yml", composed.releaseRequestDocument());
    // An SPA frontend publishes no artifact — it is built into the consuming service's image — and
    // used to get no release document at all. Every release publishes a changelog now (qits-893),
    // so it gets the synthesised one-step release half whose only publish is that changelog.
    golden("spa-frontend-release.yml", composed.releaseDocument());
  }

  /** The cli archetype's shape: a QA slot and no release slot, like spa-frontend. */
  private static final String CLI =
      """
      release-request:
        - image: qits/build-images/maven-base:latest
          timeout-seconds: 1800
          script: |
            ./mvnw -B -ntp verify
      """;

  @Test
  public void aCliArchetypeGetsTheChangelogOnlyReleaseToo() {
    CiReleaseComposer.Composed composed =
        CiReleaseComposer.compose(
            CiRepoRef.of("22222222-3333-4444-5555-666666666666", "qits", "qits-platform-access-cli"),
            slots("archetype: cli\n"),
            archetype("cli", CLI));

    golden("cli-release.yml", composed.releaseDocument());
  }

  @Test
  public void aCompositionWithNoReleaseSlotStillPublishesItsChangelog() {
    // No archetype and a release.yml with only release-request: — the rule is "every release", not
    // "every release on the two publish-free archetypes".
    CiReleaseComposer.Composed composed =
        CiReleaseComposer.compose(
            REPO,
            slots(
                """
                release-request:
                  - image: alpine:3
                    script: echo qa
                """),
            null);

    golden("release-request-only-release.yml", composed.releaseDocument());
    CiEventTrigger release =
        new CiEventTriggerParser()
            .parse(CiReleaseSlotParser.CONFIG_PATH, composed.releaseDocument());
    assertEquals(CiReleaseComposer.RELEASE_EVENT, release.eventName());
    assertEquals("version", release.checkout().branchPath());
    assertEquals("commitSha", release.checkout().shaPath());
    assertTrue(release.artifacts().isEmpty(), "a changelog is not an announced artifact");
    assertEquals(1, release.pipeline().steps().size());
    CiPipeline.CiStepDecl step = release.pipeline().steps().get(0);
    assertEquals(CiReleaseComposer.CHANGELOG_ONLY_IMAGE, step.image());
    assertEquals(CiReleaseComposer.CHANGELOG_ONLY_TIMEOUT_SECONDS, step.timeoutSeconds());
    String script = step.script();
    // The ordinary release prelude — the tag checkout and the CLI fetch — then `:`, then the
    // changelog and nothing else published.
    assertTrue(script.contains("git checkout --detach \"$QITS_VERSION\""), script);
    assertTrue(script.contains("PATH=\"/tmp/qits-bin:$PATH\""), script);
    assertTrue(script.contains("QITS_SLOT_EOF'\n:\nQITS_SLOT_EOF\n"), script);
    assertEquals(1, occurrences(script, "qits artifacts publish "), script);
    assertTrue(
        script.trim().endsWith(
            "qits artifacts publish changelog --version \"$QITS_VERSION\" --meta"
                + " git.commit.hash=\"$QITS_CI_SHA\" --meta"
                + " git.repository.name=\"$QITS_CI_REPO_NAME\""),
        script);
  }

  @Test
  public void everyReleaseHalfEndsWithTheChangelog() {
    // Whatever else a release publishes, the changelog closes the publish block of its last step,
    // behind a guard naming the cause when the step holds no CLI — after every artifact, contract
    // and docs publish, before only the SBOM submits and checks that follow every publish block —
    // and it is on no other step, and never in QA.
    String guard =
        "command -v qits > /dev/null 2>&1 || { echo \"qits-ci: the changelog cannot be published:"
            + " this release step has no qits CLI (QITS_ARTIFACTS_CLI_PACKAGE unset or the fetch"
            + " was skipped)\" >&2; exit 1; }\n";
    String changelog =
        "qits artifacts publish changelog --version \"$QITS_VERSION\" --meta"
            + " git.commit.hash=\"$QITS_CI_SHA\" --meta git.repository.name=\"$QITS_CI_REPO_NAME\"\n";
    List<CiReleaseComposer.Composed> compositions =
        List.of(
            CiReleaseComposer.compose(
                REPO, slots("archetype: java-service\n"), archetype("java-service", JAVA_SERVICE)),
            CiReleaseComposer.compose(
                REPO, slots("archetype: spa-frontend\n"), archetype("spa-frontend", SPA_FRONTEND)),
            CiReleaseComposer.compose(
                REPO,
                slots(
                    """
                    release:
                      - image: qits/build-images/ci-base:latest
                        build: true
                        script: buildctl build --opt target=image
                      - image: qits/build-images/maven-base:latest
                        script: ./mvnw -B -ntp package
                    artifacts:
                      - { type: docker, name: qits/qits-thing, sbom: out/sbom.json }
                      - { type: maven, name: "g:a", path: core, sbom: core/target/sbom.json, publish: if-changed }
                      - { type: docs, name: "@apidocs/qits-thing", path: docs/openapi.yml }
                    """),
                null));
    CiEventTriggerParser triggers = new CiEventTriggerParser();
    for (CiReleaseComposer.Composed composed : compositions) {
      List<CiPipeline.CiStepDecl> steps =
          triggers
              .parse(CiReleaseSlotParser.CONFIG_PATH, composed.releaseDocument())
              .pipeline()
              .steps();
      for (int i = 0; i < steps.size(); i++) {
        String script = steps.get(i).script();
        if (i == steps.size() - 1) {
          assertEquals(1, occurrences(script, guard + changelog), script);
          String after = script.substring(script.indexOf(guard + changelog));
          for (String publish : List.of("maven", "npm", "docker", "contract", "docs")) {
            assertFalse(after.contains("qits artifacts publish " + publish + " "), script);
          }
        } else {
          assertFalse(script.contains("publish changelog"), script);
        }
      }
      if (composed.releaseRequestDocument() != null) {
        assertFalse(composed.releaseRequestDocument().contains("changelog"));
      }
    }
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
                      docker build -t "registry.qits.$QITS_DOMAIN/qits/build-images/ci-base:$QITS_VERSION" ci-base
                      docker push "registry.qits.$QITS_DOMAIN/qits/build-images/ci-base:$QITS_VERSION"
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
                    + " \"https://registry.qits.$QITS_DOMAIN/artifacts/daemons/$QITS_ARTIFACTS_CLI_PACKAGE/$QITS_ARTIFACTS_CLI_VERSION\"\n"),
        composed.releaseDocument());
  }

  @Test
  public void everyComposedStepChecksItsLockfilesBeforeItsDeclaredScript() {
    // qits-731: no recipe rewrites a lockfile any more, so the check that a committed one resolves
    // from the platform's own registries is the platform's, and it has to be in EVERY step — a
    // step that installs from a lockfile is any step, and the composer cannot tell which. Both
    // phases, a multi-step override, an archetype's slots and a build step alike, each step read
    // back through the ordinary parser the way a run reads its document.
    List<String> documents = new java.util.ArrayList<>();
    CiReleaseComposer.Composed archetyped =
        CiReleaseComposer.compose(
            REPO, slots("archetype: java-service\n"), archetype("java-service", JAVA_SERVICE));
    documents.add(archetyped.releaseRequestDocument());
    documents.add(archetyped.releaseDocument());
    CiReleaseComposer.Composed bespoke =
        CiReleaseComposer.compose(
            REPO,
            slots(
                """
                release-request:
                  - image: qits/build-images/node-base:latest
                    script: npm ci
                  - image: docker:28-dind
                    build: true
                    script: buildctl build --frontend dockerfile.v0
                release:
                  - image: qits/build-images/ci-base:latest
                    docker: true
                    script: echo one
                  - image: qits/build-images/maven-base:latest
                    script: echo two
                artifacts:
                  - { type: docker, name: qits/thing, sbom: out/sbom.json }
                """),
            null);
    documents.add(bespoke.releaseRequestDocument());
    documents.add(bespoke.releaseDocument());

    int steps = 0;
    for (String document : documents) {
      for (CiPipeline.CiStepDecl step :
          new CiEventTriggerParser()
              .parse(CiReleaseSlotParser.CONFIG_PATH, document)
              .pipeline()
              .steps()) {
        String script = step.script();
        steps++;
        int domain = script.indexOf(": \"${QITS_DOMAIN:?");
        int written = script.indexOf("cat > " + CiReleaseComposer.LOCKFILE_CHECK + " <<'");
        int run = script.indexOf("\nsh " + CiReleaseComposer.LOCKFILE_CHECK + "\n");
        int declared = script.indexOf("# --- the declared step");
        assertTrue(domain >= 0 && domain < written, "the domain is demanded first:\n" + script);
        assertTrue(written >= 0 && written < run, "the check is written, then run:\n" + script);
        assertTrue(run < declared, "the check runs before the declared script:\n" + script);
        assertEquals(1, occurrences(script, "\nsh " + CiReleaseComposer.LOCKFILE_CHECK + "\n"));
        assertTrue(script.contains(CiReleaseComposer.lockfileOriginCheck()), script);
        // On a release step the check reads the TAG's tree, so it sits after the checkout.
        int checkout = script.indexOf("git checkout --detach \"$QITS_VERSION\"");
        assertTrue(checkout < run, "the check reads the checked-out tree:\n" + script);
      }
    }
    assertEquals(6, steps, "every step of every document was looked at");
  }

  /** A lockfile entry, the shape npm writes it in. */
  private static String lockEntry(String name, String resolved) {
    return "    \"node_modules/" + name + "\": {\n"
        + "      \"version\": \"1.0.0\",\n"
        + "      \"resolved\": \"" + resolved + "\",\n"
        + "      \"integrity\": \"sha512-x\"\n"
        + "    }";
  }

  private static String lockfile(String... entries) {
    return "{\n  \"name\": \"x\",\n  \"lockfileVersion\": 3,\n  \"packages\": {\n"
        + "    \"\": { \"name\": \"x\" },\n"
        + String.join(",\n", entries)
        + "\n  }\n}\n";
  }

  /** Runs the prelude's lockfile check under {@code sh} in {@code tree}, with only a domain set. */
  private static Map.Entry<Integer, String> runLockfileCheck(Path tree) throws Exception {
    Path check = tree.resolveSibling(tree.getFileName() + "-check.sh");
    Files.writeString(check, CiReleaseComposer.lockfileOriginCheck(), StandardCharsets.UTF_8);
    ProcessBuilder builder =
        new ProcessBuilder("sh", check.toString()).directory(tree.toFile()).redirectErrorStream(true);
    builder.environment().clear();
    builder.environment().put("PATH", System.getenv("PATH"));
    builder.environment().put("QITS_DOMAIN", "example.org");
    Process process = builder.start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    process.waitFor();
    return Map.entry(process.exitValue(), output);
  }

  @Test
  public void theLockfileCheckPassesATreeResolvedFromThePlatformsRegistries(
      @org.junit.jupiter.api.io.TempDir Path work) throws Exception {
    Path tree = Files.createDirectories(work.resolve("tree"));
    Files.writeString(
        tree.resolve("package-lock.json"),
        lockfile(
            lockEntry("left-pad", "https://mirror.qits.example.org/npm/npmjs/left-pad/-/left-pad-1.0.0.tgz"),
            lockEntry(
                "@qits/ui",
                "https://registry.qits.example.org/artifacts/npm/npm/@qits/ui/-/ui-1.0.0.tgz"),
            // A workspace link: a path, no address — not the check's business.
            lockEntry("local", "packages/local")));
    // A submodule's lockfile, the webui shape, is read too — and is good here.
    Path webui = Files.createDirectories(tree.resolve("service/src/main/webui"));
    Files.writeString(
        webui.resolve("package-lock.json"),
        lockfile(lockEntry("rxjs", "https://mirror.qits.example.org/npm/npmjs/rxjs/-/rxjs-7.0.0.tgz")));
    // An installed package's own lockfile is that package's business, whatever it names.
    Path installed = Files.createDirectories(tree.resolve("node_modules/some-dep"));
    Files.writeString(
        installed.resolve("package-lock.json"),
        lockfile(lockEntry("y", "https://registry.npmjs.org/y/-/y-1.0.0.tgz")));

    Map.Entry<Integer, String> result = runLockfileCheck(tree);

    assertEquals(0, result.getKey(), result.getValue());
    assertEquals("", result.getValue(), "a clean tree says nothing");
  }

  @Test
  public void theLockfileCheckRefusesAnInternalOriginNamingTheFileAndTheEntries(
      @org.junit.jupiter.api.io.TempDir Path work) throws Exception {
    Path tree = Files.createDirectories(work.resolve("tree"));
    Files.writeString(
        tree.resolve("package-lock.json"),
        lockfile(lockEntry("left-pad", "https://mirror.qits.example.org/npm/npmjs/left-pad/-/left-pad-1.0.0.tgz")));
    Path webui = Files.createDirectories(tree.resolve("service/src/main/webui"));
    List<String> entries = new java.util.ArrayList<>();
    for (int i = 0; i < 7; i++) {
      entries.add(
          lockEntry(
              "@qits/p" + i,
              "http://dev-qits-artifacts:8080/artifacts/npm/npm/@qits/p" + i + "/-/p" + i + "-1.tgz"));
    }
    // A host that merely CONTAINS the platform's name is not it: the prefix is compared exactly.
    entries.add(
        lockEntry("evil", "https://registry.qits.example.org.evil.test/artifacts/npm/npm/e.tgz"));
    Files.writeString(webui.resolve("package-lock.json"), lockfile(entries.toArray(String[]::new)));

    Map.Entry<Integer, String> result = runLockfileCheck(tree);

    String output = result.getValue();
    assertEquals(1, result.getKey(), output);
    assertTrue(
        output.contains(
            "qits-ci: ./service/src/main/webui/package-lock.json resolves 8 package(s) outside"
                + " https://registry.qits.example.org/ and https://mirror.qits.example.org/:"),
        output);
    assertTrue(
        output.contains("  http://dev-qits-artifacts:8080/artifacts/npm/npm/@qits/p0/-/p0-1.tgz\n"),
        output);
    assertTrue(output.contains("@qits/p4/"), "up to five entries are named:\n" + output);
    assertFalse(output.contains("@qits/p5/"), "and no more than five:\n" + output);
    assertFalse(output.contains("./package-lock.json"), "the clean file is not named:\n" + output);
    assertTrue(output.contains("no lockfile is rewritten on this platform"), output);
  }

  @Test
  public void theLockfileCheckRefusesAStepWithNoDomain(@org.junit.jupiter.api.io.TempDir Path work)
      throws Exception {
    Path check = work.resolve("check.sh");
    Files.writeString(check, CiReleaseComposer.lockfileOriginCheck(), StandardCharsets.UTF_8);
    ProcessBuilder builder =
        new ProcessBuilder("sh", check.toString()).directory(work.toFile()).redirectErrorStream(true);
    builder.environment().clear();
    builder.environment().put("PATH", System.getenv("PATH"));
    Process process = builder.start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    process.waitFor();

    assertTrue(process.exitValue() != 0, output);
    assertTrue(output.contains("this step was told no QITS_DOMAIN"), output);
  }

  @Test
  public void theCliDownloadCarriesTheEdgeBearerOnBothArms() {
    // epic qits-441: a release-phase step on the EDGE plane authenticates with this run's ci-run
    // token, $QITS_TOKEN — the CLI download is anonymous otherwise and a 401 in 0s through the
    // public edge. The composer emits no per-deployment branch for this; the choice is made at
    // RUN TIME by the same `set --`/`"$@"` idiom StepContainerSettings.BOOTSTRAP already uses for the
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
                + " \"https://registry.qits.$QITS_DOMAIN/artifacts/daemons/$QITS_ARTIFACTS_CLI_PACKAGE/$QITS_ARTIFACTS_CLI_VERSION\"\n"),
        document);
    assertTrue(
        document.contains(
            "wget -q \"$@\" -O /tmp/qits-bin/qits"
                + " \"https://registry.qits.$QITS_DOMAIN/artifacts/daemons/$QITS_ARTIFACTS_CLI_PACKAGE/$QITS_ARTIFACTS_CLI_VERSION\"\n"),
        document);
    // The `set --` block sits ONCE, before both arms, rather than once per arm — a second copy
    // would be a second place for the two to drift.
    assertEquals(1, occurrences(document, "set --\n"));
  }

  @Test
  public void theDockerBuildSecretFilesCarryTheRunToken() {
    // The two files a buildctl `--secret id=…` mount reads for the maven mirror's credential are
    // written from the run's token and its subject: a step holds $QITS_TOKEN and no commissioned
    // pair, and the branch that preferred a pair was deleted with the internal plane (qits-515).
    CiReleaseComposer.Composed composed =
        CiReleaseComposer.compose(
            REPO, slots("archetype: java-service\n"), archetype("java-service", JAVA_SERVICE));

    String document = composed.releaseDocument();
    assertFalse(document.contains("QITS_COMMISSIONED_CLIENT"), document);
    assertTrue(
        document.contains(
            "      (\n"
                + "        umask 077\n"
                + "        if [ -n \"${QITS_TOKEN:-}\" ]; then\n"
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
  public void theDockerBuildSecretFilesReachARealShellCorrectlyInEveryArm() throws Exception {
    // Same shape as the CLI-download execution test below: the string assertion above proves
    // what bytes are emitted, this proves a shell reads them the way the comment claims, with a
    // token and its subject, with a token alone, and with neither.
    CiReleaseComposer.Composed composed =
        CiReleaseComposer.compose(
            REPO, slots("archetype: java-service\n"), archetype("java-service", JAVA_SERVICE));
    String fragment = extractSecretFilesFragment(composed.releaseDocument());

    Path work = Files.createTempDirectory("docker-secret-fragment");
    try {
      Path scriptFile = work.resolve("run.sh");
      Files.writeString(scriptFile, fragment);

      // A commissioned pair in the environment is read by nothing: the token is what is written.
      run(scriptFile, work, Map.of(
          "QITS_COMMISSIONED_CLIENT_ID", "the-client-id",
          "QITS_COMMISSIONED_CLIENT_SECRET", "the-client-secret",
          "QITS_TOKEN", "the-run-token",
          "QITS_TOKEN_SUBJECT", "the-run-subject"));
      assertEquals("the-run-subject", Files.readString(work.resolve("qits-client-id")));
      assertEquals("the-run-token", Files.readString(work.resolve("qits-client-secret")));

      // The token and its subject.
      run(scriptFile, work, Map.of(
          "QITS_TOKEN", "the-run-token",
          "QITS_TOKEN_SUBJECT", "the-run-subject"));
      assertEquals("the-run-subject", Files.readString(work.resolve("qits-client-id")));
      assertEquals("the-run-token", Files.readString(work.resolve("qits-client-secret")));

      // A token and no subject — an older qits-ci: still a real id rather than an empty one.
      run(scriptFile, work, Map.of("QITS_TOKEN", "the-run-token"));
      assertEquals("qits-ci-run", Files.readString(work.resolve("qits-client-id")));
      assertEquals("the-run-token", Files.readString(work.resolve("qits-client-secret")));

      // No token: both files empty rather than the script failing under -eu on an unset
      // variable.
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
      withToken.environment().put("QITS_DOMAIN", "example.invalid");
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
      noToken.environment().put("QITS_DOMAIN", "example.invalid");
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
                + "if true; then");
  }

  // --- the properties the goldens are there to hold ------------------------------------------------

  @Test
  public void anIfChangedEntryReachesTheComposedDocumentAndOnlyThatOne() {
    // The join reads the run's trigger document, never release.yml, so the policy has to survive
    // composition; the default is not emitted, which is what keeps every golden byte-identical.
    CiReleaseComposer.Composed composed =
        CiReleaseComposer.compose(
            REPO,
            slots(
                """
                release:
                  - image: qits/build-images/maven-base:latest
                    script: ./mvnw -B -ntp package
                artifacts:
                  - { type: maven, name: "eu.wohlben.qits:qits-thing", sbom: target/sbom.json, publish: if-changed }
                  - { type: docker, name: qits/qits-thing, sbom: out/sbom.json }
                """),
            null);

    assertTrue(
        composed
            .releaseDocument()
            .contains(
                "  - { type: 'maven', name: 'eu.wohlben.qits:qits-thing', publish: 'if-changed' }\n"),
        composed.releaseDocument());
    assertTrue(
        composed.releaseDocument().contains("  - { type: 'docker', name: 'qits/qits-thing' }\n"),
        composed.releaseDocument());
    assertFalse(composed.releaseDocument().contains("announce"), composed.releaseDocument());
    CiEventTrigger release =
        new CiEventTriggerParser()
            .parse(CiReleaseSlotParser.CONFIG_PATH, composed.releaseDocument());
    assertEquals(CiArtifact.Publish.IF_CHANGED, release.artifacts().get(0).publish());
    assertEquals(CiArtifact.Publish.ALWAYS, release.artifacts().get(1).publish());
  }

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
                            + "artifacts:\n  - { type: docker, name: qits/qits-ci, sbom:"
                            + " out/sbom.json }\n"),
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
  public void everyReleaseStepSubmitsTheSbomsItHoldsAndOnlyTheLastChecksThem() {
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

    // The composer cannot see which step writes the document, so every step submits it when it is
    // there — and each submit sits AFTER that step's own script, before its exit code, which is what
    // makes "SBOM before green" structural. The presence check is the last step's alone.
    String document = composed.releaseDocument();
    int build = document.indexOf("buildctl build --opt target=binary");
    int firstSubmit = document.indexOf("qits artifacts publish sbom submit");
    int docs = document.indexOf("npm run docs");
    int secondSubmit = document.indexOf("qits artifacts publish sbom submit", docs);
    int check = document.indexOf("qits artifacts publish exists sbom 'docker/qits/qits-ci'");
    assertTrue(
        build > 0
            && firstSubmit > build
            && docs > firstSubmit
            && secondSubmit > docs
            && check > secondSubmit,
        document);
    assertEquals(2, occurrences(document, "if [ -f 'out/sbom.json' ]; then"), document);
    assertEquals(1, occurrences(document, "publish exists sbom"), document);
  }

  /**
   * qits-754: the QA document of every packaged archetype that has a {@code release-request:} slot
   * outside a container build, composed from the recipe exactly as it ships. The fixtures above
   * prove the composer; these prove the recipes — so a change to what a QA step runs (the JaCoCo
   * agent, the vitest reporters and the coverage provider) is a diff of the composed text a person
   * reads line by line, the same as a change to the prelude. Each declaration is the one the
   * recipe's own header names.
   */
  @TestFactory
  public List<DynamicTest> thePackagedArchetypesComposeTheirQaDocuments() {
    Map<String, String> declarations =
        Map.of(
            "java-service",
            """
            archetype: java-service
            artifacts:
              - { type: docker, name: qits/qits-ci, sbom: .sbom/sbom.json }
            userflows: qits-ci
            """,
            "maven-library",
            """
            archetype: maven-library
            artifacts:
              - { type: maven, name: "eu.wohlben.qits:qits-eventstream", sbom: target/sbom.json }
            """,
            "cli",
            "archetype: cli\n",
            "spa-frontend",
            "archetype: spa-frontend\n",
            "app",
            """
            archetype: app
            artifacts:
              - { type: docker, name: qits/qits-landing, sbom: .sbom/sbom.json }
            """,
            "npm-library",
            """
            archetype: npm-library
            artifacts:
              - { type: npm, name: "@qits/ui-components", path: dist/qits-spa-ui-components, sbom: sbom.json }
            """);
    return declarations.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(
            entry ->
                DynamicTest.dynamicTest(
                    entry.getKey(),
                    () ->
                        golden(
                            "packaged-" + entry.getKey() + "-release-request.yml",
                            CiReleaseComposer.compose(
                                    CiRepoRef.of(
                                        "77777777-8888-9999-aaaa-bbbbbbbbbbbb",
                                        "qits",
                                        "qits-" + entry.getKey() + "-example"),
                                    slots(entry.getValue()),
                                    packaged(entry.getKey()))
                                .releaseRequestDocument())))
        .toList();
  }

  // --- the publishing postlude (qits-620) ---------------------------------------------------------

  /** A packaged archetype recipe, exactly as qits-ci ships it. */
  private CiReleaseSlots packaged(String name) throws IOException {
    String resource = CiReleaseArchetypes.PACKAGED_DIR + name + CiEventTriggerParser.CONFIG_SUFFIX;
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
      assertNotNull(in, resource);
      return archetype(name, new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  /**
   * qits-registries-javalib's shape, declared OUT of dependency order on purpose: the entry that
   * links both siblings comes first. The postlude decides each linked sibling before anything
   * linking it, keeps declared order otherwise, and resolves each artifactId to its full GAV. All
   * three are if-changed, so they are one link group: asked with --dry-run first, and published
   * together or not at all.
   */
  @Test
  public void aLinkedReactorOnMavenLibraryPublishesInLinkOrder() throws Exception {
    CiReleaseComposer.Composed composed =
        CiReleaseComposer.compose(
            CiRepoRef.of("33333333-4444-5555-6666-777777777777", "qits", "qits-registries-javalib"),
            slots(
                """
                archetype: maven-library
                artifacts:
                  - { type: maven, name: "eu.wohlben.qits:qits-registries-npm", path: npm, sbom: npm/target/sbom.json, link: [qits-blobstore, qits-registries-common], publish: if-changed }
                  - { type: maven, name: "eu.wohlben.qits:qits-blobstore", path: blobstore, sbom: blobstore/target/sbom.json, publish: if-changed }
                  - { type: maven, name: "eu.wohlben.qits:qits-registries-common", path: common, sbom: common/target/sbom.json, link: [qits-blobstore], publish: if-changed }
                """),
            packaged("maven-library"));

    golden("maven-library-link-reactor-release.yml", composed.releaseDocument());

    String document = composed.releaseDocument();
    int blobstore = document.indexOf("publish maven --name 'eu.wohlben.qits:qits-blobstore'");
    int common = document.indexOf("publish maven --name 'eu.wohlben.qits:qits-registries-common'");
    int npm = document.indexOf("publish maven --name 'eu.wohlben.qits:qits-registries-npm'");
    assertTrue(blobstore > 0 && common > blobstore && npm > common, document);
    assertTrue(
        document.contains(
            "--link 'eu.wohlben.qits:qits-blobstore' --link 'eu.wohlben.qits:qits-registries-common'"
                + " --if-changed --dry-run --version \"$QITS_VERSION\")"),
        document);
    // Every dry run comes before the first real publish, and a changed group publishes
    // unconditionally, so a linked sibling sits at the release version.
    int lastDryRun = document.lastIndexOf("--dry-run");
    int firstGate = document.indexOf("= changed ]; then");
    assertTrue(lastDryRun > 0 && firstGate > lastDryRun, document);
    assertTrue(
        document.contains(
            "  qits_published_0=$(qits artifacts publish maven --name 'eu.wohlben.qits:qits-registries-npm'"
                + " --path 'npm' --sbom 'npm/target/sbom.json'"
                + " --link 'eu.wohlben.qits:qits-blobstore' --link 'eu.wohlben.qits:qits-registries-common'"
                + " --version \"$QITS_VERSION\")"),
        document);
    assertTrue(document.contains("  qits_published_0=$qits_dry_run_0\n"), document);
    // The archetype only builds now: no deploy, no bearer of its own, no pom probe.
    assertFalse(document.contains("deploy -DskipTests"), document);
    assertFalse(document.contains("altDeploymentRepository"), document);
    assertFalse(document.contains("qits-recipe-deploy-settings"), document);
  }

  @Test
  public void anIfChangedEntryNobodyLinksIsDecidedAlone() throws Exception {
    CiReleaseSlots slots =
        slots(
            """
            artifacts:
              - { type: maven, name: "g:a", sbom: a/sbom.json, publish: if-changed }
              - { type: maven, name: "g:b", sbom: b/sbom.json, link: [c], publish: always }
              - { type: maven, name: "g:c", sbom: c/sbom.json, publish: always }
              - { type: maven, name: "g:d", sbom: d/sbom.json, link: [e], publish: if-changed }
              - { type: maven, name: "g:e", sbom: e/sbom.json, publish: if-changed }
            """);
    int[] groups = CiReleaseComposer.ifChangedLinkGroups(slots.artifacts());
    assertEquals(-1, groups[0], "a lone if-changed entry is no group");
    assertEquals(-1, groups[1], "an always group needs no dry run");
    assertEquals(-1, groups[2]);
    assertTrue(groups[3] >= 0 && groups[3] == groups[4], java.util.Arrays.toString(groups));
  }

  @Test
  public void theTopologicalOrderIsStableToDeclaredOrder() {
    CiReleaseSlots slots =
        slots(
            """
            artifacts:
              - { type: maven, name: "g:d", link: [b], sbom: d/sbom.json }
              - { type: npm, name: "@qits/n", sbom: n/sbom.json }
              - { type: maven, name: "g:c", sbom: c/sbom.json }
              - { type: maven, name: "g:b", link: [c], sbom: b/sbom.json }
              - { type: docker, name: qits/x, sbom: out/sbom.json }
              - { type: maven, name: "g:a", sbom: a/sbom.json }
            """);
    // c is the first entry free to go; b waits on it; d waits on b; npm, c and a keep their places.
    assertEquals(List.of(1, 2, 3, 0, 5), CiReleaseComposer.publishOrder(slots.artifacts()));
  }

  @Test
  public void anNpmLibraryPublishesTheBuiltPackageDirectory() throws Exception {
    CiReleaseComposer.Composed composed =
        CiReleaseComposer.compose(
            CiRepoRef.of("44444444-5555-6666-7777-888888888888", "qits", "qits-ui-components-jslib"),
            slots(
                """
                archetype: npm-library
                artifacts:
                  - { type: npm, name: "@qits/ui-components", path: dist/qits-spa-ui-components, sbom: sbom.json }
                  - { type: docs, name: "@qits/ui-components" }
                """),
            packaged("npm-library"));

    golden("npm-library-release.yml", composed.releaseDocument());

    String document = composed.releaseDocument();
    assertTrue(
        document.contains(
            "qits artifacts publish npm --name '@qits/ui-components' --path"
                + " 'dist/qits-spa-ui-components' --sbom 'sbom.json' --if-changed --version"
                + " \"$QITS_VERSION\")\n"),
        document);
    assertFalse(document.contains("npm plan"), document);
    assertFalse(document.contains("npm publish \""), document);
    assertFalse(document.contains("--tag main"), document);
    // The workbench docs entry has no path: it stays the recipe's own storybook publish.
    assertFalse(document.contains("--openapi"), document);
  }

  /** qits-projects after qits-645: no release: override, contracts and @apidocs from the platform. */
  @Test
  public void aJavaServiceWithContractsAndApidocsPublishesThemAfterTheBuild() {
    CiReleaseComposer.Composed composed =
        CiReleaseComposer.compose(
            CiRepoRef.of("55555555-6666-7777-8888-999999999999", "qits", "qits-projects-service"),
            slots(
                """
                archetype: java-service
                artifacts:
                  - { type: docker, name: qits/qits-projects, sbom: .sbom/sbom.json }
                  - { type: docs, name: "@apidocs/qits-projects", path: docs/openapi.yml }
                contracts:
                  application: qits-projects
                  golden-masters: { from: golden-masters/, packages: [maven, npm] }
                userflows: qits-projects
                """),
            archetype("java-service", JAVA_SERVICE));

    golden("java-service-contracts-release.yml", composed.releaseDocument());

    String document = composed.releaseDocument();
    assertTrue(
        document.contains(
            "  - { type: 'maven', name: 'eu.wohlben.qits:qits-projects-golden-masters', publish:"
                + " 'if-changed', section: 'contracts' }\n"
                + "  - { type: 'npm', name: '@qits/projects-golden-masters', publish: 'if-changed',"
                + " section: 'contracts' }\n"),
        document);
    int maven = document.indexOf("publish contract --kind 'golden-masters' --ecosystem 'maven'");
    int npm = document.indexOf("publish contract --kind 'golden-masters' --ecosystem 'npm'");
    int docs = document.indexOf("publish contract-docs --application 'qits-projects'");
    int apidocs = document.indexOf("publish docs submit --site '@apidocs/qits-projects' --openapi");
    int sbom = document.indexOf("publish sbom submit --type 'docker'");
    int built = document.indexOf("bash -eu /tmp/qits-slot.sh");
    assertTrue(
        built > 0 && maven > built && npm > maven && docs > npm && apidocs > docs && sbom > apidocs,
        document);
    assertTrue(
        document.contains(
            "--package 'maven=eu.wohlben.qits:qits-projects-golden-masters' --package"
                + " 'npm=@qits/projects-golden-masters'"),
        document);

    // The expansion is an ordinary trigger document the join reads back: one if-changed row per
    // contract package, and the @apidocs entry an ordinary always row.
    CiEventTrigger release =
        new CiEventTriggerParser().parse(CiReleaseSlotParser.CONFIG_PATH, document);
    assertEquals(4, release.artifacts().size());
    assertEquals(CiArtifact.Publish.ALWAYS, release.artifacts().get(1).publish());
    assertEquals(CiArtifact.Publish.IF_CHANGED, release.artifacts().get(2).publish());
    assertEquals(CiArtifact.Publish.IF_CHANGED, release.artifacts().get(3).publish());
    // qits-666: the contract packages are marked, so the join can say which section declared them.
    assertEquals(CiArtifact.Section.ARTIFACTS, release.artifacts().get(0).section());
    assertEquals(CiArtifact.Section.ARTIFACTS, release.artifacts().get(1).section());
    assertEquals(CiArtifact.Section.CONTRACTS, release.artifacts().get(2).section());
    assertEquals(CiArtifact.Section.CONTRACTS, release.artifacts().get(3).section());
  }

  /**
   * qits-653: the packaged {@code app} recipe, exactly as it ships, with the declaration its header
   * now names. Its one release step generates {@code .sbom/sbom.json} after the image push, and that
   * same step — the only one, so also the last — submits it and checks it arrived.
   */
  @Test
  public void theAppArchetypeGeneratesItsImageSbomAndThePostludeSubmitsIt() throws Exception {
    CiReleaseComposer.Composed composed =
        CiReleaseComposer.compose(
            CiRepoRef.of("66666666-7777-8888-9999-000000000000", "qits", "qits-landing-app"),
            slots(
                """
                archetype: app
                artifacts:
                  - { type: docker, name: qits/qits-landing, sbom: .sbom/sbom.json }
                """),
            packaged("app"));

    String document = composed.releaseDocument();
    golden("app-release.yml", document);
    int push = document.indexOf("--output \"type=image,name=$ref,push=true\"");
    // No `-t pnpm` between the pin and the flags: an -app's lockfile is package-lock.json.
    int generate =
        document.indexOf("@cyclonedx/cdxgen@11.2.7 --spec-version 1.6 -o .sbom/sbom.json .");
    int submit = document.indexOf("if [ -f '.sbom/sbom.json' ]; then");
    int check = document.indexOf("qits artifacts publish exists sbom 'docker/qits/qits-landing'");
    assertTrue(push > 0 && generate > push && submit > generate && check > submit, document);
  }

  /**
   * qits-bootstrap-cli (qits-1149): the packaged cli archetype publishes nothing but its consumer
   * pacts, so its release slot is one step that builds nothing, and the pacts are packed after it.
   */
  @Test
  public void aCliWithPactsPublishesThemFromThePackagedReleaseSlot() throws IOException {
    CiReleaseComposer.Composed composed =
        CiReleaseComposer.compose(
            CiRepoRef.of("55555555-6666-7777-8888-999999999999", "qits", "qits-bootstrap-cli"),
            slots(
                """
                archetype: cli
                contracts:
                  application: qits-bootstrap-cli
                  pacts:
                    qits-projects-service: { packages: [maven] }
                """),
            packaged("cli"));

    golden("packaged-cli-pacts-release.yml", composed.releaseDocument());
    assertTrue(
        composed
            .releaseDocument()
            .contains(
                "qits artifacts publish contract --kind 'pacts' --ecosystem 'maven' --name"
                    + " 'eu.wohlben.qits:qits-bootstrap-cli-pacts-qits-projects-service'"),
        composed.releaseDocument());
  }

  /**
   * qits-landing-app: an app, a docker image and two consumer pacts, keyed by the providers'
   * repository names and packed from the one flat {@code pacts/}.
   */
  @Test
  public void anAppWithPactsPublishesThemAndNoContractDocs() {
    CiReleaseComposer.Composed composed =
        CiReleaseComposer.compose(
            CiRepoRef.of("66666666-7777-8888-9999-000000000000", "qits", "qits-landing-app"),
            slots(
                """
                archetype: app
                artifacts:
                  - { type: docker, name: qits/qits-landing, sbom: .sbom/sbom.json }
                contracts:
                  application: qits-landing
                  pacts:
                    qits-projects-service: { packages: [maven] }
                    qits-githost-service: { packages: [maven] }
                """),
            archetype(
                "app",
                """
                release-request:
                  - image: qits/build-images/ci-base:latest
                    build: true
                    script: buildctl build --frontend dockerfile.v0 --local context=.
                release:
                  - image: qits/build-images/ci-base:latest
                    build: true
                    timeout-seconds: 1800
                    script: |
                      npm ci && npm run test:pacts
                      buildctl build --frontend dockerfile.v0 --local context=. \\
                        --output "type=image,name=registry.qits.$QITS_DOMAIN/qits/qits-landing:$QITS_VERSION,push=true"
                """));

    golden("app-pacts-release.yml", composed.releaseDocument());

    String document = composed.releaseDocument();
    assertTrue(
        document.contains(
            "qits artifacts publish contract --kind 'pacts' --ecosystem 'maven' --name"
                + " 'eu.wohlben.qits:qits-landing-app-pacts-qits-projects-service' --application"
                + " 'qits-landing' --provider 'qits-projects-service' --from 'pacts/' --version"
                + " \"$QITS_VERSION\"\n"),
        document);
    assertTrue(
        document.contains(
            "qits artifacts publish contract --kind 'pacts' --ecosystem 'maven' --name"
                + " 'eu.wohlben.qits:qits-landing-app-pacts-qits-githost-service' --application"
                + " 'qits-landing' --provider 'qits-githost-service' --from 'pacts/' --version"
                + " \"$QITS_VERSION\"\n"),
        document);
    assertFalse(document.contains("contract-docs"), "no golden masters, no contract docs");
    assertTrue(document.contains("sbom submit"), document);
  }

  /**
   * qits-664: {@code sbom:} is now mandatory on every non-{@code docs} entry, so the shape this test
   * used to cover — a maven or npm entry publishing with no SBOM at all — can no longer be parsed out
   * of a {@code release.yml}. The refusal itself is {@code CiReleaseSlotParserTest}'s to hold; what is
   * left provable here is that a maven or npm entry that DOES declare {@code sbom:} carries the flag
   * and submits it, which is the ordinary path every entry takes now.
   */
  @Test
  public void aMavenOrNpmEntryWithAnSbomPublishesWithTheFlagAndSubmitsIt() {
    String document =
        CiReleaseComposer.compose(
                CiRepoRef.of("77777777-8888-9999-0000-111111111111", "qits", "qits-thing-service"),
                slots(
                    """
                    release:
                      - image: qits/build-images/maven-base:latest
                        script: ./mvnw -B -ntp package
                    artifacts:
                      - { type: maven, name: "eu.wohlben.qits:qits-ci-daemon-protocol", path: ci-daemon-protocol, sbom: ci-daemon-protocol/target/sbom.json, publish: always }
                      - { type: maven, name: "eu.wohlben.qits:qits-workspace-editor-image", sbom: target/sbom.json, publish: always }
                      - { type: npm, name: "@qits/thing", path: dist/thing, sbom: dist/thing/sbom.json, publish: always }
                    """),
                null)
            .releaseDocument();

    assertTrue(
        document.contains(
            "qits artifacts publish maven --name 'eu.wohlben.qits:qits-ci-daemon-protocol' --path"
                + " 'ci-daemon-protocol' --sbom 'ci-daemon-protocol/target/sbom.json' --version"
                + " \"$QITS_VERSION\"\n"),
        document);
    assertTrue(
        document.contains(
            "qits artifacts publish maven --name 'eu.wohlben.qits:qits-workspace-editor-image'"
                + " --path '.' --sbom 'target/sbom.json' --version \"$QITS_VERSION\"\n"),
        document);
    assertTrue(
        document.contains(
            "qits artifacts publish npm --name '@qits/thing' --path 'dist/thing' --sbom"
                + " 'dist/thing/sbom.json' --version \"$QITS_VERSION\"\n"),
        document);
    assertTrue(document.contains("sbom submit"), document);
  }

  /**
   * qits-621 (a): the shape that broke one-submit-step — an image step writing {@code
   * .sbom/sbom.json}, then a maven-base step writing {@code core/target/sbom.json}. Both steps carry
   * the guarded submit for both entries; only the last carries the presence checks.
   */
  @Test
  public void aTwoStepOverrideSubmitsBothSbomsOnBothStepsAndChecksThemOnTheLast() {
    CiReleaseComposer.Composed composed =
        CiReleaseComposer.compose(
            REPO,
            slots(
                """
                release:
                  - image: qits/build-images/ci-base:latest
                    build: true
                    script: |
                      buildctl build --opt target=image
                      buildctl build --opt target=sbom --output type=local,dest=.sbom
                  - image: qits/build-images/maven-base:latest
                    script: ./mvnw -B -ntp package
                artifacts:
                  - { type: docker, name: qits/qits-thing, sbom: .sbom/sbom.json }
                  - { type: maven, name: "eu.wohlben.qits:qits-thing-client", path: core, sbom: core/target/sbom.json, publish: always }
                """),
            null);

    String document = composed.releaseDocument();
    golden("two-step-override-sboms-release.yml", document);
    int maven = document.indexOf("./mvnw -B -ntp package");
    String image = document.substring(0, maven);
    String last = document.substring(maven);
    for (String step : List.of(image, last)) {
      assertEquals(1, occurrences(step, "if [ -f '.sbom/sbom.json' ]; then"), step);
      assertEquals(1, occurrences(step, "if [ -f 'core/target/sbom.json' ]; then"), step);
    }
    assertFalse(image.contains("publish exists sbom"), image);
    assertTrue(
        last.contains(
            "qits artifacts publish exists sbom 'docker/qits/qits-thing' \"$QITS_VERSION\" \\\n"),
        last);
    assertTrue(
        last.contains(
            "|| { echo '.config/qits/release.yml declares sbom: core/target/sbom.json for maven"
                + " eu.wohlben.qits:qits-thing-client, and no release step produced it' >&2;"
                + " exit 1; }"),
        last);
    // The publishes still come first on the last step, so a submitted SBOM describes an upload.
    assertTrue(
        last.indexOf("qits artifacts publish maven") < last.indexOf("publish sbom submit"), last);
  }

  /**
   * An {@code if-changed} entry used to be a composition error when its SBOM would have been
   * submitted from an earlier step than the publish. Every step submits now, so it composes — and
   * the entry's submit and presence check are the publishing step's alone, behind its answer.
   */
  @Test
  public void anIfChangedSbomIsTheLastStepsAloneInATwoStepRelease() {
    CiReleaseComposer.Composed composed =
        CiReleaseComposer.compose(
            REPO,
            slots(
                """
                release:
                  - image: qits/build-images/ci-base:latest
                    build: true
                    script: buildctl build --opt target=image
                  - image: qits/build-images/maven-base:latest
                    script: ./mvnw -B -ntp package
                artifacts:
                  - { type: docker, name: qits/qits-thing, sbom: out/sbom.json }
                  - { type: maven, name: "g:a", sbom: target/sbom.json, publish: if-changed }
                """),
            null);

    String document = composed.releaseDocument();
    int maven = document.indexOf("./mvnw -B -ntp package");
    String image = document.substring(0, maven);
    String last = document.substring(maven);
    assertFalse(image.contains("target/sbom.json"), image);
    assertTrue(
        last.contains(
            "case \"$qits_published_1\" in published\\ *) qits artifacts publish sbom submit"
                + " --type 'maven' --name 'g:a' --version \"$QITS_VERSION\" --file"
                + " 'target/sbom.json' ;; esac\n"),
        last);
    assertTrue(
        last.contains(
            "case \"$qits_published_1\" in published\\ *)\n"
                + "        qits artifacts publish exists sbom 'maven/g:a' \"$QITS_VERSION\""),
        last);
  }

  @Test
  public void thePublishBlockGoesOnTheLastStepAndTheSbomsKeepTheirs() {
    CiReleaseComposer.Composed composed =
        CiReleaseComposer.compose(
            REPO,
            slots(
                """
                release:
                  - image: qits/build-images/ci-base:latest
                    build: true
                    script: buildctl build --opt target=image
                  - image: qits/build-images/maven-base:latest
                    script: ./mvnw -B -ntp package
                artifacts:
                  - { type: docker, name: qits/qits-thing, sbom: out/sbom.json }
                  - { type: maven, name: "g:a", path: core, sbom: core/target/sbom.json }
                """),
            null);

    String document = composed.releaseDocument();
    int image = document.indexOf("buildctl build --opt target=image");
    int submit = document.indexOf("publish sbom submit --type 'docker'");
    int maven = document.indexOf("./mvnw -B -ntp package");
    int publish = document.indexOf("qits artifacts publish maven --name 'g:a' --path 'core'");
    assertTrue(image > 0 && submit > image && maven > submit && publish > maven, document);
    assertEquals(2, occurrences(document, "# --- platform postlude"), document);
  }

  @Test
  public void contractsWithNoReleasePipelineAreRefused() {
    CiConfigException refused =
        assertThrows(
            CiConfigException.class,
            () ->
                CiReleaseComposer.compose(
                    REPO,
                    slots(
                        "release-request:\n  - {image: alpine:3, script: echo qa}\n"
                            + "contracts:\n  application: qits-x\n  golden-masters: { from: gm/,"
                            + " packages: [maven] }\n"),
                    null));
    assertTrue(refused.getMessage().contains("declares contracts but"), refused.getMessage());
  }

  @Test
  public void anArtifactRestatingAContractCoordinateIsRefused() {
    CiConfigException refused =
        assertThrows(
            CiConfigException.class,
            () ->
                CiReleaseComposer.compose(
                    REPO,
                    slots(
                        """
                        release:
                          - image: alpine:3
                            script: echo build
                        artifacts:
                          - { type: maven, name: "eu.wohlben.qits:qits-x-golden-masters", sbom: target/sbom.json }
                        contracts:
                          application: qits-x
                          golden-masters: { from: gm/, packages: [maven] }
                        """),
                    null));
    assertTrue(
        refused.getMessage().contains("'eu.wohlben.qits:qits-x-golden-masters'"),
        refused.getMessage());
  }

  /**
   * The postlude as behaviour, not text: run under {@code sh -eu} with a stub {@code qits} that
   * records its argv and answers as the store would. An {@code unchanged since} answer must submit
   * no SBOM; a {@code published} one must; a refusal must fail the step before anything after it.
   */
  @Test
  public void anIfChangedSbomIsSubmittedOnlyAfterPublishedAndARefusalFailsTheStep()
      throws Exception {
    CiReleaseComposer.Composed composed =
        CiReleaseComposer.compose(
            REPO,
            slots(
                """
                release:
                  - image: alpine:3
                    script: echo built
                artifacts:
                  - { type: maven, name: "g:a", sbom: target/sbom.json, publish: if-changed }
                  - { type: npm, name: "@qits/b", path: dist/b, sbom: sbom.json, publish: always }
                """),
            null);
    String postlude = extractPostlude(composed.releaseDocument());

    Path work = Files.createTempDirectory("publish-postlude");
    try {
      Path bin = work.resolve("bin");
      Files.createDirectories(bin);
      Path stub = bin.resolve("qits");
      Files.writeString(
          stub,
          "#!/bin/sh\n"
              + "printf '%s\\n' \"$*\" >> \""
              + work.resolve("argv.txt")
              + "\"\n"
              + "case \"$*\" in\n"
              + "  *'--if-changed'*) [ \"$ANSWER\" = refuse ] && exit 1; echo \"$ANSWER\" ;;\n"
              + "  'artifacts publish npm'*) echo 'published 2026.1002.1' ;;\n"
              + "esac\n");
      stub.toFile().setExecutable(true);
      Path script = work.resolve("postlude.sh");
      Files.writeString(script, postlude);
      // The step built both documents; the always entry's submit is guarded on the file.
      Files.createDirectories(work.resolve("target"));
      Files.writeString(work.resolve("target/sbom.json"), "{}");
      Files.writeString(work.resolve("sbom.json"), "{}");

      int unchanged = runPostlude(script, work, bin, "unchanged since 2026.1001.1");
      String argv = Files.readString(work.resolve("argv.txt"));
      assertEquals(0, unchanged, argv);
      assertFalse(argv.contains("sbom submit --type maven"), argv);
      assertTrue(argv.contains("sbom submit --type npm"), "an always entry submits regardless");
      // Nothing was published at this version, so there is nothing for the check to find.
      assertFalse(argv.contains("exists sbom maven/g:a"), argv);
      assertTrue(argv.contains("exists sbom npm/@qits/b 2026.1002.1"), argv);

      Files.delete(work.resolve("argv.txt"));
      int published = runPostlude(script, work, bin, "published 2026.1002.1");
      argv = Files.readString(work.resolve("argv.txt"));
      assertEquals(0, published, argv);
      assertTrue(argv.contains("sbom submit --type maven --name g:a"), argv);
      assertTrue(argv.contains("exists sbom maven/g:a 2026.1002.1"), argv);
      // And the changelog, with the step's own provenance (qits-893).
      assertTrue(
          argv.contains(
              "artifacts publish changelog --version 2026.1002.1 --meta git.commit.hash="
                  + "c".repeat(40)
                  + " --meta git.repository.name=qits-thing"),
          argv);

      Files.delete(work.resolve("argv.txt"));
      int refusedExit = runPostlude(script, work, bin, "refuse");
      argv = Files.readString(work.resolve("argv.txt"));
      assertTrue(refusedExit != 0, "a refused publish must fail the step");
      assertFalse(argv.contains("sbom submit"), "nothing after the refusal runs: " + argv);
      assertFalse(argv.contains("publish changelog"), "not even the changelog: " + argv);
    } finally {
      try (var stream = Files.walk(work)) {
        stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
      }
    }
  }

  /**
   * qits-621 (b), as behaviour: a single release step whose script did not write the declared
   * document submits nothing (the file guard) and then fails at the presence check, naming the
   * entry and its path. With the document present, the same step submits it and goes green.
   */
  @Test
  public void aSingleStepReleaseFailsNamingAnSbomNoStepProduced() throws Exception {
    CiReleaseComposer.Composed composed =
        CiReleaseComposer.compose(
            REPO,
            slots(
                """
                release:
                  - image: alpine:3
                    build: true
                    script: echo built
                artifacts:
                  - { type: docker, name: qits/qits-thing, sbom: .sbom/sbom.json }
                """),
            null);
    String postlude = extractPostlude(composed.releaseDocument());

    Path work = Files.createTempDirectory("sbom-presence");
    try {
      Path bin = work.resolve("bin");
      Files.createDirectories(bin);
      Path stub = bin.resolve("qits");
      // The stub store holds an SBOM exactly when one was submitted in this run.
      Files.writeString(
          stub,
          "#!/bin/sh\n"
              + "printf '%s\\n' \"$*\" >> \""
              + work.resolve("argv.txt")
              + "\"\n"
              + "case \"$*\" in\n"
              + "  'artifacts publish exists'*) grep -q 'sbom submit' \""
              + work.resolve("argv.txt")
              + "\" ;;\n"
              + "esac\n");
      stub.toFile().setExecutable(true);
      Path script = work.resolve("postlude.sh");
      Files.writeString(script, postlude);

      ProcessBuilder pb =
          new ProcessBuilder("/bin/sh", "-eu", script.toString())
              .directory(work.toFile())
              .redirectErrorStream(true);
      pb.environment().clear();
      pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
      pb.environment().put("QITS_ARTIFACTS_CLI_PACKAGE", "qits");
      pb.environment().put("QITS_VERSION", "2026.1002.1");
      pb.environment().put("QITS_CI_SHA", "c".repeat(40));
      pb.environment().put("QITS_CI_REPO_NAME", "qits-thing");
      Process absent = pb.start();
      String output = new String(absent.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(absent.waitFor() != 0, output);
      assertTrue(
          output.contains(
              ".config/qits/release.yml declares sbom: .sbom/sbom.json for docker qits/qits-thing,"
                  + " and no release step produced it"),
          output);
      assertFalse(Files.readString(work.resolve("argv.txt")).contains("sbom submit"));

      Files.delete(work.resolve("argv.txt"));
      Files.createDirectories(work.resolve(".sbom"));
      Files.writeString(work.resolve(".sbom/sbom.json"), "{}");
      Process present = pb.start();
      output = new String(present.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertEquals(0, present.waitFor(), output);
      String argv = Files.readString(work.resolve("argv.txt"));
      assertTrue(
          argv.contains(
              "artifacts publish sbom submit --type docker --name qits/qits-thing --version"
                  + " 2026.1002.1 --file .sbom/sbom.json"),
          argv);
      assertTrue(argv.contains("artifacts publish exists sbom docker/qits/qits-thing 2026.1002.1"));
    } finally {
      try (var stream = Files.walk(work)) {
        stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
      }
    }
  }

  private static int runPostlude(Path script, Path work, Path bin, String answer)
      throws Exception {
    ProcessBuilder pb =
        new ProcessBuilder("/bin/sh", "-eu", script.toString())
            .directory(work.toFile())
            .redirectErrorStream(true);
    pb.environment().clear();
    pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
    pb.environment().put("QITS_ARTIFACTS_CLI_PACKAGE", "qits");
    pb.environment().put("QITS_VERSION", "2026.1002.1");
    pb.environment().put("QITS_CI_SHA", "c".repeat(40));
    pb.environment().put("QITS_CI_REPO_NAME", "qits-thing");
    pb.environment().put("ANSWER", answer);
    Process p = pb.start();
    p.getInputStream().readAllBytes();
    return p.waitFor();
  }

  /** The step's postlude, from its header to the end of the script, as a plain shell script. */
  private static String extractPostlude(String document) {
    int begin = document.indexOf("      # --- platform postlude");
    assertTrue(begin >= 0, document);
    StringBuilder out = new StringBuilder();
    for (String line : document.substring(begin).split("\n", -1)) {
      out.append(line.length() >= 6 ? line.substring(6) : line).append('\n');
    }
    return out.toString();
  }

  // --- the QA report hook (qits-754) ---------------------------------------------------------------

  /**
   * The QA report hook, EXECUTED: a whole composed {@code release-request:} step under {@code sh},
   * with a stub {@code curl} that "downloads" a stub {@code qits} recording its argv and the run
   * coordinates it inherited. The goldens prove the bytes; this proves the verdict — the step exits
   * with the declared script's code whatever the hook does, and the hook is told that code.
   *
   * <p>The seam is the test's own copy of the text: every {@code /tmp/} path in it (the slot script,
   * the lockfile check, the hook, {@code /tmp/qits-cli}) is moved under a scratch directory, the way
   * {@code QitsCliPinIT} moves the download base. Production text is untouched, and no network is
   * dialled — the stub curl answers the fetch.
   */
  @Test
  public void theQaReportHookRunsWhateverTheExitCodeAndNeverChangesIt() throws Exception {
    CiReleaseComposer.Composed composed =
        CiReleaseComposer.compose(
            REPO,
            slots(
                """
                release-request:
                  - image: alpine:3
                    script: |
                      echo declared-script-ran
                      exit "$QITS_TEST_EXIT"
                """),
            null);
    Path work = Files.createTempDirectory("qa-report-hook");
    try {
      String script = stepScript(composed.releaseRequestDocument()).replace("/tmp/", work + "/");
      Path scriptFile = work.resolve("step.sh");
      Files.writeString(scriptFile, script);
      Path checkout = Files.createDirectories(work.resolve("checkout"));
      Files.writeString(
          work.resolve("stub-qits"),
          "#!/bin/sh\n"
              + "printf '%s\\n' \"$*\" > '" + work.resolve("qits-argv.txt") + "'\n"
              + "printf '%s %s %s\\n' \"$QITS_CI_RUN_ID\" \"$QITS_CI_STEP_INDEX\""
              + " \"$QITS_PUBLISH_TOKEN_COMMAND\" > '" + work.resolve("qits-env.txt") + "'\n"
              + "exit \"${QITS_STUB_EXIT:-0}\"\n");
      Path bin = Files.createDirectories(work.resolve("bin"));
      Path curl = bin.resolve("curl");
      Files.writeString(
          curl,
          "#!/bin/sh\n"
              + "printf '%s\\n' \"$*\" >> '" + work.resolve("curl-argv.txt") + "'\n"
              + "prev=\n"
              + "for a in \"$@\"; do\n"
              + "  if [ \"$prev\" = \"-o\" ]; then cp '" + work.resolve("stub-qits") + "' \"$a\"; fi\n"
              + "  prev=\"$a\"\n"
              + "done\n");
      assertTrue(curl.toFile().setExecutable(true));
      String withCurl = bin + ":" + System.getenv("PATH");

      // The declared script's exit 3 is the step's exit 3, and the hook was told 3.
      HookRun red = runQaStep(scriptFile, checkout, withCurl, Map.of("QITS_TEST_EXIT", "3"));
      assertEquals(3, red.exit(), red.output());
      assertTrue(red.output().contains("declared-script-ran"), red.output());
      assertEquals("ci report submit --exit-code 3\n", read(work, "qits-argv.txt"), red.output());
      // The run coordinates and the CLI's bearer command reach the hook's child by inheritance.
      assertEquals("run-1 0 /tmp/qits-publish-token\n", read(work, "qits-env.txt"));
      String fetched = read(work, "curl-argv.txt");
      assertTrue(fetched.contains("--header Authorization: Bearer the-run-token"), fetched);
      assertTrue(
          fetched.contains(
              "-o " + work + "/qits-cli/qits https://registry.qits.example.invalid/artifacts/daemons/"
                  + "qits-platform-access-cli/2026.1006.1"),
          fetched);
      assertFalse(red.output().contains("reports were not submitted"), red.output());

      // Green stays green, and is reported as 0.
      HookRun green = runQaStep(scriptFile, checkout, withCurl, Map.of("QITS_TEST_EXIT", "0"));
      assertEquals(0, green.exit(), green.output());
      assertEquals("ci report submit --exit-code 0\n", read(work, "qits-argv.txt"));

      // No token: no header, and still a fetch (the edge's answer is the CLI's problem, not ours).
      Files.deleteIfExists(work.resolve("curl-argv.txt"));
      Map<String, String> noToken = new java.util.HashMap<>(Map.of("QITS_TEST_EXIT", "0"));
      noToken.put("QITS_TOKEN", null);
      HookRun anonymous = runQaStep(scriptFile, checkout, withCurl, noToken);
      assertEquals(0, anonymous.exit(), anonymous.output());
      assertFalse(read(work, "curl-argv.txt").contains("Authorization"), read(work, "curl-argv.txt"));

      // A hook that fails changes nothing: red stays 3, green stays 0, and it says so.
      for (String code : List.of("3", "0")) {
        HookRun refused =
            runQaStep(
                scriptFile, checkout, withCurl, Map.of("QITS_TEST_EXIT", code, "QITS_STUB_EXIT", "1"));
        assertEquals(Integer.parseInt(code), refused.exit(), refused.output());
        assertTrue(
            refused.output().contains("reports were not submitted; the step's verdict is unchanged"),
            refused.output());
      }

      // An image with neither curl nor wget: the fetch refuses, the verdict does not move.
      Path bare = Files.createDirectories(work.resolve("bare-bin"));
      for (String tool :
          List.of("sh", "cat", "mkdir", "chmod", "find", "grep", "sed", "awk", "head", "wc", "tr")) {
        Files.createSymbolicLink(bare.resolve(tool), which(tool));
      }
      for (String code : List.of("3", "0")) {
        HookRun noFetcher =
            runQaStep(scriptFile, checkout, bare.toString(), Map.of("QITS_TEST_EXIT", code));
        assertEquals(Integer.parseInt(code), noFetcher.exit(), noFetcher.output());
        assertTrue(noFetcher.output().contains("has neither curl nor wget"), noFetcher.output());
        assertTrue(
            noFetcher.output().contains("reports were not submitted"), noFetcher.output());
        assertTrue(noFetcher.output().contains("declared-script-ran"), noFetcher.output());
      }

      // No CLI configured — empty, and unset outright: `set -u` holds, the hook skips and succeeds.
      Files.deleteIfExists(work.resolve("curl-argv.txt"));
      Files.deleteIfExists(work.resolve("qits-argv.txt"));
      for (String pkg : java.util.Arrays.asList("", null)) {
        Map<String, String> env = new java.util.HashMap<>(Map.of("QITS_TEST_EXIT", "3"));
        env.put("QITS_ARTIFACTS_CLI_PACKAGE", pkg);
        HookRun skipped = runQaStep(scriptFile, checkout, withCurl, env);
        assertEquals(3, skipped.exit(), skipped.output());
        assertTrue(
            skipped.output().contains("reports skipped: no qits CLI configured"), skipped.output());
        assertFalse(skipped.output().contains("not submitted"), skipped.output());
        assertFalse(skipped.output().contains("unbound"), skipped.output());
        assertFalse(skipped.output().contains("parameter not set"), skipped.output());
      }
      assertFalse(Files.exists(work.resolve("curl-argv.txt")), "nothing is fetched with no CLI");
      assertFalse(Files.exists(work.resolve("qits-argv.txt")), "nothing is submitted with no CLI");
    } finally {
      try (var stream = Files.walk(work)) {
        stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
      }
    }
  }

  @Test
  public void onlyQaStepsCarryTheReportHook() {
    CiReleaseComposer.Composed composed =
        CiReleaseComposer.compose(
            REPO, slots("archetype: java-service\n"), archetype("java-service", JAVA_SERVICE));
    String qa = composed.releaseRequestDocument();
    assertEquals(1, occurrences(qa, "sh /tmp/qits-report-hook.sh \"$qits_step_exit\" ||"), qa);
    assertTrue(qa.trim().endsWith("exit \"$qits_step_exit\""), qa);
    // The QA hook's fetch never touches the step's own positional parameters.
    assertFalse(qa.contains("set --"), qa);
    String release = composed.releaseDocument();
    assertFalse(release.contains("qits-report-hook"), release);
    assertFalse(release.contains("qits_step_exit"), release);
    assertFalse(release.contains("set +e"), release);
  }

  private record HookRun(int exit, String output) {}

  /** Runs a composed step script the way the daemon would, with the platform's step environment. */
  private static HookRun runQaStep(
      Path scriptFile, Path checkout, String path, Map<String, String> overrides) throws Exception {
    ProcessBuilder pb =
        new ProcessBuilder("/bin/sh", scriptFile.toString())
            .directory(checkout.toFile())
            .redirectErrorStream(true);
    Map<String, String> env = pb.environment();
    env.clear();
    env.put("PATH", path);
    env.put("QITS_DOMAIN", "example.invalid");
    env.put("QITS_ARTIFACTS_CLI_PACKAGE", "qits-platform-access-cli");
    env.put("QITS_ARTIFACTS_CLI_VERSION", "2026.1006.1");
    env.put("QITS_TOKEN", "the-run-token");
    env.put("QITS_CI_RUN_ID", "run-1");
    env.put("QITS_CI_STEP_INDEX", "0");
    env.put("QITS_PUBLISH_TOKEN_COMMAND", "/tmp/qits-publish-token");
    overrides.forEach(
        (key, value) -> {
          if (value == null) {
            env.remove(key);
          } else {
            env.put(key, value);
          }
        });
    Process process = pb.start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    return new HookRun(process.waitFor(), output);
  }

  /** A composed document's one step script, as a plain shell script. */
  private static String stepScript(String document) {
    int begin = document.indexOf("    script: |\n");
    assertTrue(begin >= 0, document);
    assertEquals(-1, document.indexOf("    script: |\n", begin + 1), "one step expected");
    StringBuilder out = new StringBuilder();
    for (String line : document.substring(begin + "    script: |\n".length()).split("\n", -1)) {
      out.append(line.length() >= 6 ? line.substring(6) : line).append('\n');
    }
    return out.toString();
  }

  private static String read(Path work, String name) throws IOException {
    return Files.readString(work.resolve(name));
  }

  private static Path which(String tool) {
    for (String dir : System.getenv("PATH").split(":")) {
      Path candidate = Path.of(dir, tool);
      if (Files.isExecutable(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException(tool + " is not on PATH");
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
