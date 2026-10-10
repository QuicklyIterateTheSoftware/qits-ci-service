package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiPipeline.CiStepDecl;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The packaged archetype set, as a set: every recipe this module keeps under {@code
 * src/main/resources/release-archetypes/} is in this jar, is a recipe, and composes.
 *
 * <p><b>This is the cover for the recipes no CI run of this repository executes.</b> qits-ci-service
 * is a {@code java-service}, so its own release request reads {@code java-service.yml}'s source
 * file at its fold and really runs it; the other seven ship to the estate inside the jar with nothing having
 * run them. Until qits-583 that was true of all eight — they lived in the wrapper, whose own {@code
 * release.yml} names no archetype — so a recipe that did not parse was found out by the first
 * repository to release on it.
 *
 * <p><b>What is and is not checked, stated exactly</b>, because "the script is valid" is easy to
 * overclaim:
 *
 * <ul>
 *   <li>the classpath resource exists and is byte-identical to the file — so Maven did not filter a
 *       {@code ${...}} out of a script on the way into the jar;
 *   <li>it parses with {@code parseArchetype} and declares a {@code release-request:} slot.
 *       qits-projects' {@code ReleaseGates} arms the CI gate on the mere presence of {@code
 *       archetype:} in a repository's file, so a recipe with no QA slot would hang every release
 *       request that named it;
 *   <li>it composes through {@link CiReleaseComposer} for a repository that declares nothing but
 *       {@code archetype: <name>} and, where the recipe has a {@code release:} slot, again with an
 *       {@code artifacts:} entry carrying an {@code sbom:} — which is what makes the composer emit
 *       its postlude — and the composed documents parse back as trigger files;
 *   <li>every composed step script passes {@code sh -n} and {@code bash -n}: the daemon runs that
 *       outer text under bash where the step image has it and under sh where it does not, so it
 *       must be both. <b>That check cannot see the recipe's own script</b>: the composer carries it
 *       inside a quoted heredoc, which a shell's {@code -n} reads as data;
 *   <li>so each recipe's declared script body is checked directly as well — under {@code bash -n}
 *       when its step image is one of the platform's {@code qits/build-images/*}, which carry bash
 *       and are therefore where the composed runner picks {@code bash -eu}, and under {@code sh -n}
 *       for any other image, where nothing says bash exists;
 *   <li>no declared step reaches the platform without the run's credential on the EDGE plane — the
 *       five textual rules {@link #everyDeclaredStepPresentsTheRunsCredentialOnTheEdgePlane} states,
 *       each one a failure a live run on an EDGE runner has already produced. Text, not behaviour:
 *       it proves a recipe still SPELLS the credential, never that the edge accepts it.
 * </ul>
 *
 * <p><b>{@code -n} is a SYNTAX check by whichever {@code sh} and {@code bash} this host has</b> (on
 * a Debian build image {@code sh} is dash; in a step container it is busybox ash). It finds an
 * unterminated quote, a stray {@code fi}, a heredoc with no end. It does not find a command that
 * does not exist, a variable that is unset, or a construct dash accepts and ash does not, and no
 * script is executed here. Both shells are required: a host without one fails this test rather than
 * skipping it, because a skip is exactly how seven unexecuted recipes would go unchecked again.
 *
 * <p>Plain JUnit, for {@code CiReleaseComposerTest}'s reason: parser, composer and classpath, no
 * application.
 */
public class PackagedReleaseArchetypesTest {

  /** This module's own copy, relative to the {@code ci} module surefire runs in. */
  private static final Path SOURCE = Path.of("src", "main", "resources", "release-archetypes");

  private static final CiRepoRef REPO = CiRepoRef.of("repo-1", "qits", "qits-target");

  private final CiReleaseSlotParser slotParser = new CiReleaseSlotParser();
  private final CiEventTriggerParser triggerParser = new CiEventTriggerParser();

  private static List<String> names() throws Exception {
    assertTrue(Files.isDirectory(SOURCE), "no archetype directory at " + SOURCE.toAbsolutePath());
    try (Stream<Path> files = Files.list(SOURCE)) {
      List<String> names =
          files
              .map(file -> file.getFileName().toString())
              .filter(file -> file.endsWith(CiEventTriggerParser.CONFIG_SUFFIX))
              .map(
                  file ->
                      file.substring(0, file.length() - CiEventTriggerParser.CONFIG_SUFFIX.length()))
              .sorted()
              .toList();
      assertFalse(names.isEmpty(), "no recipe at all under " + SOURCE.toAbsolutePath());
      return names;
    }
  }

  private static String packaged(String name) throws Exception {
    String resource = CiReleaseArchetypes.PACKAGED_DIR + name + CiEventTriggerParser.CONFIG_SUFFIX;
    try (InputStream in =
        PackagedReleaseArchetypesTest.class.getClassLoader().getResourceAsStream(resource)) {
      assertNotNull(in, resource + " is not on the classpath — check ci/pom.xml's resources");
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private CiReleaseSlots recipe(String name) throws Exception {
    return slotParser.parseArchetype(CiReleaseArchetypes.sourcePath(name), packaged(name));
  }

  @Test
  public void theBootGuardsSetIsExactlyTheFilesThisRepositoryCarries() throws Exception {
    // Both directions: a ninth file nobody named would ship unguarded, and a name whose file went
    // would fail every boot.
    assertEquals(
        new java.util.TreeSet<>(names()),
        new java.util.TreeSet<>(CiReleaseArchetypes.REQUIRED_PACKAGED),
        "CiReleaseArchetypes.REQUIRED_PACKAGED and src/main/resources/release-archetypes/ disagree");
  }

  @Test
  public void theBootGuardPassesOnTheRealClasspathAndNamesEveryRecipeThatIsMissing()
      throws Exception {
    CiReleaseArchetypes real = new CiReleaseArchetypes();
    real.slotParser = slotParser;
    real.applicationVersion = Optional.empty();
    real.requirePackaged();

    // A binary built without the resource include, staged through the packaged-content seam: two
    // recipes absent, one present and broken, the rest the real ones.
    CiReleaseArchetypes stripped =
        new CiReleaseArchetypes() {
          @Override
          String packagedContent(String resource) {
            if (resource.endsWith("/oci.yml") || resource.endsWith("/cli.yml")) {
              return null;
            }
            if (resource.endsWith("/daemon.yml")) {
              return "archetype: another\n";
            }
            return super.packagedContent(resource);
          }
        };
    stripped.slotParser = slotParser;
    stripped.applicationVersion = Optional.empty();

    IllegalStateException refused =
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class, stripped::requirePackaged);
    assertTrue(refused.getMessage().contains("[cli, daemon, oci]"), refused.getMessage());
    assertFalse(refused.getMessage().contains("spa-frontend"), refused.getMessage());
    assertTrue(
        refused.getMessage().contains("quarkus.native.resources.includes"), refused.getMessage());
    assertTrue(refused.getMessage().contains("ci/pom.xml"), refused.getMessage());
  }

  @Test
  public void everyArchetypeFileIsOnTheClasspathByteForByte() throws Exception {
    for (String name : names()) {
      assertTrue(CiReleaseSlotParser.isArchetypeName(name), name + " is not a name qits-ci reads");
      assertEquals(
          Files.readString(SOURCE.resolve(name + CiEventTriggerParser.CONFIG_SUFFIX)),
          packaged(name),
          name + ": the packaged recipe is not the file — was it filtered?");
    }
  }

  @Test
  public void everyPackagedArchetypeParsesAndDeclaresAReleaseRequestSlot() throws Exception {
    for (String name : names()) {
      CiReleaseSlots recipe = recipe(name);
      assertNotNull(recipe.releaseRequest(), name + " declares no release-request: slot");
      assertFalse(
          recipe.releaseRequest().steps().isEmpty(), name + " declares an empty release-request:");
    }
  }

  @Test
  public void everyPackagedArchetypeIsWhatTheResolverAnswersWhenARepositoryCarriesNone()
      throws Exception {
    // The seam itself, against the real classpath: a repository with no recipe of its own gets the
    // packaged one, recorded with no rev and this qits-ci's version.
    CiReleaseArchetypes archetypes = new CiReleaseArchetypes();
    archetypes.configSource = new FakeCiConfigSource();
    archetypes.slotParser = slotParser;
    archetypes.applicationVersion = Optional.of("2026.930.1");
    for (String name : names()) {
      CiReleaseArchetypes.Resolution found = archetypes.read(REPO, "f".repeat(40), name);
      assertEquals(CiReleaseArchetypes.Status.FOUND, found.status(), name + ": " + found.detail());
      assertEquals(null, found.archetype().rev(), name);
      assertEquals("2026.930.1", found.archetype().version(), name);
    }
  }

  @Test
  public void everyPackagedArchetypeComposesAndEveryScriptPassesASyntaxCheck(@TempDir Path dir)
      throws Exception {
    for (String name : names()) {
      CiReleaseSlots recipe = recipe(name);
      List<CiReleaseComposer.Composed> compositions = new ArrayList<>();
      compositions.add(
          CiReleaseComposer.compose(
              REPO,
              slotParser.parse(CiReleaseSlotParser.CONFIG_PATH, "archetype: " + name + "\n"),
              recipe));
      if (recipe.release() != null) {
        // With a declaration to publish and an SBOM to submit, which is what makes the composer
        // emit its postlude — the half of the platform text a bare `archetype:` never reaches.
        compositions.add(
            CiReleaseComposer.compose(
                REPO,
                slotParser.parse(
                    CiReleaseSlotParser.CONFIG_PATH,
                    "archetype: "
                        + name
                        + "\nartifacts:\n"
                        + "  - { type: docker, name: qits/qits-target, sbom: target/sbom.json }\n"),
                recipe));
      }
      for (CiReleaseComposer.Composed composed : compositions) {
        assertNotNull(composed.releaseRequestDocument(), name + " composes no QA document");
        // Every composition has a release half, a recipe with no release: slot included: the
        // composer synthesises one that publishes the changelog (qits-893).
        assertNotNull(composed.releaseDocument(), name + " composes no release document");
        for (String document :
            List.of(composed.releaseRequestDocument(), composed.releaseDocument())) {
          CiEventTrigger trigger = triggerParser.parse(CiReleaseSlotParser.CONFIG_PATH, document);
          assertFalse(trigger.pipeline().steps().isEmpty(), name + " composed no steps");
          for (CiStepDecl step : trigger.pipeline().steps()) {
            // The OUTER text: runs under whichever of the two the image has, so it must be both.
            syntax(dir, "sh", step.script(), name + " (composed)");
            syntax(dir, "bash", step.script(), name + " (composed)");
          }
        }
      }
      // The recipe's OWN scripts, which sit in a quoted heredoc above and were therefore read as
      // data by both checks. Checked under the shell the composed runner will really pick.
      for (CiPipeline slot :
          Stream.of(recipe.releaseRequest(), recipe.release()).filter(s -> s != null).toList()) {
        for (CiStepDecl step : slot.steps()) {
          String shell = step.image().startsWith("qits/build-images/") ? "bash" : "sh";
          syntax(dir, shell, step.script(), name + " (declared, " + step.image() + ")");
        }
      }
    }
  }

  /**
   * <b>On an EDGE runner every platform address is the public edge, which refuses an anonymous
   * read</b> (qits-443: the platform's only executor is one). What a step holds there is {@code
   * $QITS_TOKEN} and what qits-ci derives from it — {@code $QITS_MAVEN_AUTH_USR}/{@code _PSW} for a
   * repository's own {@code -s .qits-maven-settings.xml}, the {@code -gs} in {@code MAVEN_ARGS}, the
   * publish-token command, {@code ~/.npmrc}, and the composed {@code /tmp/qits-client-id}/{@code
   * -secret} files — and it holds NO commissioned pair. Each rule below is a way a recipe has
   * thrown that away on a live run:
   *
   * <ol>
   *   <li><b>never assign {@code QITS_MAVEN_AUTH_USR}/{@code _PSW}, never name the commissioned
   *       pair.</b> Run b92c5a85 (qits-workspace-editor-oci's own recipe, 2026-09-30): {@code
   *       QITS_MAVEN_AUTH_USR="${QITS_COMMISSIONED_CLIENT_ID-}" ./mvnw …} overwrote the credential
   *       the plane had injected with a pair that does not exist there, and the mirror answered 401
   *       on the first plugin pom;
   *   <li><b>every {@code curl}/{@code wget} spends a {@code "$@"} bearer list</b> built from the
   *       token — the same run's {@code curl: (22) … 401}, and maven-library's before it (eaf249d6);
   *   <li><b>a script that writes {@code ~/.npmrc} writes the {@code _authToken} lines too</b>: its
   *       {@code >} replaces the file the bootstrap put the token in (8265733a, {@code
   *       ERR_PNPM_FETCH_401});
   *   <li><b>a {@code buildctl build} handed a platform address as a build arg is handed the
   *       credential for it as a secret</b> — the composed pair files for a maven address, the npm
   *       token file for an npm one, {@code $tarball_auth} for the musl tarballs (qits-541);
   *   <li>and the census: each rule met at least one command, so a refactor that hides every {@code
   *       curl} behind a helper fails here rather than passing on nothing.
   * </ol>
   */
  /**
   * qits-1171: a @QuarkusTest's classes have no code source location, so the agent records them
   * only with {@code inclnolocationclasses}, and the CLI reads the classes Quarkus rewrote as it
   * loaded them only from the agent's class dump. Without either, they count as never run.
   */
  @Test
  public void everyCoverageAgentDumpsTheClassesItLoadedBesideTheExecFile() throws Exception {
    int agents = 0;
    for (String name : names()) {
      String recipe = packaged(name);
      for (String line : recipe.split("\n")) {
        if (line.contains("-javaagent:$jacoco_agent=")) {
          agents++;
          assertTrue(
              line.contains("destfile=$PWD/.qits-reports/jacoco.exec")
                  && line.contains("inclnolocationclasses=true")
                  && line.contains("classdumpdir=$PWD/.qits-reports/jacoco-classes"),
              name + " runs the coverage agent without its class dump: " + line.strip());
        }
      }
    }
    assertTrue(agents >= 3, "expected the coverage agent in java-service, maven-library and cli");
  }

  @Test
  public void everyDeclaredStepPresentsTheRunsCredentialOnTheEdgePlane() throws Exception {
    int fetches = 0;
    int npmrcs = 0;
    int mavenBuilds = 0;
    int npmBuilds = 0;
    int tarballBuilds = 0;
    for (String name : names()) {
      CiReleaseSlots recipe = recipe(name);
      for (CiPipeline slot :
          Stream.of(recipe.releaseRequest(), recipe.release()).filter(s -> s != null).toList()) {
        for (CiStepDecl step : slot.steps()) {
          String what = name + " (" + step.image() + ")";
          List<String> commands = commands(step.script());
          String code = String.join("\n", commands);
          for (String command : commands) {
            assertFalse(
                command.matches("(?s).*\\bQITS_MAVEN_AUTH_(USR|PSW)=.*"),
                what + " overwrites the maven credential the plane injected: " + command);
            assertFalse(
                command.contains("QITS_COMMISSIONED_CLIENT_"),
                what + " names the commissioned pair, which an EDGE step does not hold: " + command);
            if (command.matches("(?s)(.*[\\s(|;&!])?(curl|wget)\\s.*")) {
              fetches++;
              assertTrue(
                  command.contains("\"$@\""),
                  what + " fetches without the bearer list: " + command);
              assertTrue(
                  code.contains("QITS_TOKEN") || code.contains("QITS_PUBLISH_TOKEN_COMMAND"),
                  what + " fetches in a script that never reads the run's token");
            }
            if (command.contains("buildctl build")) {
              if (command.contains("build-arg:QITS_MAVEN_")) {
                mavenBuilds++;
                assertTrue(
                    command.contains("--secret id=qits-client-id,src=/tmp/qits-client-id")
                        && command.contains(
                            "--secret id=qits-client-secret,src=/tmp/qits-client-secret"),
                    what + " builds against the maven stores with no credential: " + command);
              }
              if (command.contains("build-arg:QITS_NPM_")) {
                npmBuilds++;
                assertTrue(
                    command.contains("--secret id=qits-npm-token,src=/tmp/qits-npm-token")
                        && code.contains("QITS_PUBLISH_TOKEN_COMMAND"),
                    what + " builds against the npm stores with no credential: " + command);
              }
              if (command.contains("build-arg:MUSL_URL")) {
                tarballBuilds++;
                assertTrue(
                    command.contains("$tarball_auth")
                        && code.contains("HTTP_AUTH_TOKEN_$tarball_host,env=QITS_TOKEN"),
                    what + " ADDs a registry tarball with no credential: " + command);
              }
            }
          }
          if (code.contains("> ~/.npmrc")) {
            npmrcs++;
            assertTrue(
                code.contains(":_authToken=") && code.contains("QITS_PUBLISH_TOKEN_COMMAND"),
                what + " replaces ~/.npmrc and leaves the run's token out of it");
          }
        }
      }
    }
    // One since qits-620: maven-library's pom probe went with its deploy, and the CLI owns that
    // check now. What remains is java-service's userflow PUT.
    assertTrue(fetches >= 1, "only " + fetches + " curl/wget commands were found to check");
    assertTrue(npmrcs >= 2, "only " + npmrcs + " ~/.npmrc writers were found to check");
    assertTrue(mavenBuilds >= 5, "only " + mavenBuilds + " maven builds were found to check");
    assertTrue(npmBuilds >= 1, "only " + npmBuilds + " npm builds were found to check");
    assertTrue(tarballBuilds >= 2, "only " + tarballBuilds + " tarball builds were found to check");
  }

  /**
   * A script as its commands: comment lines dropped, a {@code \}-continued line joined to the one
   * it continues. Line-wise and deliberately crude — enough to read one {@code curl} or one {@code
   * buildctl build} as a whole, which is all the rules above ask.
   */
  private static List<String> commands(String script) {
    List<String> commands = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (String line : script.split("\n")) {
      String trimmed = line.strip();
      if (trimmed.isEmpty() || trimmed.startsWith("#")) {
        continue;
      }
      if (trimmed.endsWith("\\")) {
        current.append(trimmed, 0, trimmed.length() - 1).append(' ');
        continue;
      }
      commands.add(current.append(trimmed).toString());
      current.setLength(0);
    }
    if (current.length() > 0) {
      commands.add(current.toString());
    }
    return commands;
  }

  /** {@code <shell> -n} over one script: a parse, not a run. A missing shell is a failure. */
  private static void syntax(Path dir, String shell, String script, String what) throws Exception {
    Path file = Files.createTempFile(dir, "script", ".sh");
    Files.writeString(file, script);
    Process process;
    try {
      process =
          new ProcessBuilder(shell, "-n", file.toString()).redirectErrorStream(true).start();
    } catch (java.io.IOException e) {
      throw new AssertionError(
          "no '" + shell + "' on this host, so " + what + " could not be syntax-checked", e);
    }
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(process.waitFor(30, TimeUnit.SECONDS), shell + " -n did not return for " + what);
    assertEquals(0, process.exitValue(), shell + " -n refused " + what + ":\n" + output);
  }
}
