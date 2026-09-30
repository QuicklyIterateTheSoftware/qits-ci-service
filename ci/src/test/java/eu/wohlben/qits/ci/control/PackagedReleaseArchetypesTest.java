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
 * The packaged archetype set, as a set: every recipe this repository keeps under {@code
 * .config/qits/release-archetypes/} is in this jar, is a recipe, and composes.
 *
 * <p><b>This is the cover for the recipes no CI run of this repository executes.</b> qits-ci-service
 * is a {@code java-service}, so its own release request reads {@code java-service.yml} locally at
 * its fold and really runs it; the other seven ship to the estate inside the jar with nothing having
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
 *       for any other image, where nothing says bash exists.
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

  /** This repository's own copy, relative to the {@code ci} module surefire runs in. */
  private static final Path SOURCE = Path.of("..", ".config", "qits", "release-archetypes");

  /** The eight the estate's {@code release.yml} files name today. More may be added; none removed. */
  private static final List<String> KNOWN =
      List.of(
          "app", "cli", "daemon", "java-service", "maven-library", "npm-library", "oci",
          "spa-frontend");

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
      assertTrue(names.containsAll(KNOWN), "an archetype the estate names is gone: " + names);
      return names;
    }
  }

  private static String packaged(String name) throws Exception {
    String resource = CiReleaseArchetypes.PACKAGED_DIR + name + CiEventTriggerParser.CONFIG_SUFFIX;
    try (InputStream in =
        PackagedReleaseArchetypesTest.class.getClassLoader().getResourceAsStream(resource)) {
      assertNotNull(in, resource + " is not on the classpath — ci/pom.xml's <resource> is broken");
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private CiReleaseSlots recipe(String name) throws Exception {
    return slotParser.parseArchetype(CiReleaseSlotParser.archetypePath(name), packaged(name));
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
        assertEquals(
            recipe.release() != null,
            composed.releaseDocument() != null,
            name + ": a release document exists exactly when the recipe has a release: slot");
        for (String document :
            Stream.of(composed.releaseRequestDocument(), composed.releaseDocument())
                .filter(text -> text != null)
                .toList()) {
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
