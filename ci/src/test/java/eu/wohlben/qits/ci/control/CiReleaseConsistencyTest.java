package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The release-QA consistency check (qits-1400): {@code release.yml}'s docker artifact and {@code
 * contracts: application:} against {@code deployments.yml}'s application, and the QA document a
 * refused fold composes.
 *
 * <p>Plain JUnit and no fakes: {@link CiReleaseConsistency} is a pure function of the two files, and
 * the composed refusal is run under a real {@code sh} so that "the first QA step exits 1 naming
 * both values" is observed rather than read off the text.
 */
public class CiReleaseConsistencyTest {

  private static final CiRepoRef REPO =
      CiRepoRef.of("2f1c9b3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f", "qits", "qits-foo-service");

  private final CiReleaseSlotParser parser = new CiReleaseSlotParser();

  private CiReleaseSlots slots(String content) {
    return parser.parse(CiReleaseSlotParser.CONFIG_PATH, content);
  }

  /** A java-service's file: one docker entry and a contracts section, both naming {@code image}. */
  private CiReleaseSlots release(String image, String contractsApplication) {
    return slots(
        "archetype: java-service\n"
            + "artifacts:\n"
            + "  - { type: docker, name: "
            + image
            + ", sbom: .sbom/sbom.json }\n"
            + (contractsApplication == null
                ? ""
                : "contracts:\n"
                    + "  application: "
                    + contractsApplication
                    + "\n"
                    + "  golden-masters: { from: golden-masters/, packages: [maven] }\n"));
  }

  private static String deployments(String application) {
    return "# how this deploys\n"
        + (application == null ? "" : "application: " + application + "\n")
        + "routes: /foo\n";
  }

  // --- the rule ---------------------------------------------------------------------------------

  @Test
  public void matchingNamesPass() {
    assertNull(
        CiReleaseConsistency.refusal(REPO, release("qits/foo", "foo"), deployments("foo")));
  }

  @Test
  public void aMismatchedDockerNameIsRefusedNamingBothValues() {
    String refusal =
        CiReleaseConsistency.refusal(REPO, release("qits/foo", null), deployments("bar"));

    assertEquals(
        "release.yml's docker artifact is `qits/foo` but deployments.yml's application is `bar`:"
            + " they must move together (qits/bar)",
        refusal);
  }

  @Test
  public void aMismatchedContractsApplicationIsRefusedNamingBothValues() {
    String refusal =
        CiReleaseConsistency.refusal(REPO, release("qits/bar", "foo"), deployments("bar"));

    assertEquals(
        "release.yml's contracts application is `foo` but deployments.yml's application is `bar`:"
            + " they must move together (bar)",
        refusal);
  }

  @Test
  public void bothMismatchesAreNamedInOneRefusal() {
    String refusal =
        CiReleaseConsistency.refusal(REPO, release("qits/foo", "foo"), deployments("bar"));

    assertNotNull(refusal);
    assertTrue(refusal.contains("docker artifact is `qits/foo`"), refusal);
    assertTrue(refusal.contains("contracts application is `foo`"), refusal);
  }

  @Test
  public void anAbsentApplicationFallsBackToTheRepositoryName() {
    // The repository name IS the application then: qits/<repository> passes…
    assertNull(
        CiReleaseConsistency.refusal(
            REPO, release("qits/qits-foo-service", "qits-foo-service"), deployments(null)));
    // …and anything else is refused against the repository name, saying where it came from.
    String refusal =
        CiReleaseConsistency.refusal(REPO, release("qits/foo", null), deployments(null));
    assertEquals(
        "release.yml's docker artifact is `qits/foo` but deployments.yml's application is"
            + " `qits-foo-service` (the repository name: it declares no `application:`): they must"
            + " move together (qits/qits-foo-service)",
        refusal);
  }

  @Test
  public void aRepositoryWithNoDeploymentsYmlIsSkipped() {
    assertNull(CiReleaseConsistency.refusal(REPO, release("qits/anything", "anything"), null));
  }

  @Test
  public void aReleaseYmlWithNoDockerArtifactIsOnlyComparedOnWhatItDeclares() {
    CiReleaseSlots noDocker =
        slots(
            "archetype: daemon\n"
                + "artifacts:\n"
                + "  - { type: daemon, name: qits-foo-daemon, sbom: out/sbom.json }\n");
    assertNull(CiReleaseConsistency.refusal(REPO, noDocker, deployments("bar")));
    assertNull(
        CiReleaseConsistency.refusal(REPO, slots("archetype: java-service\n"), deployments("bar")));
  }

  @Test
  public void anUnreadableDeploymentsYmlIsNotThisChecksRefusalToMake() {
    assertNull(
        CiReleaseConsistency.refusal(REPO, release("qits/foo", null), "application: [unclosed\n"));
  }

  // --- the composed refusal ---------------------------------------------------------------------

  private static final String QA_TWO_STEPS =
      """
      release-request:
        - image: qits/build-images/ci-base:latest
          script: echo first-step-ran
        - image: qits/build-images/ci-base:latest
          script: echo second-step-ran
      artifacts:
        - { type: docker, name: qits/foo, sbom: .sbom/sbom.json }
      release:
        - image: qits/build-images/ci-base:latest
          build: true
          script: buildctl build
      """;

  /** The composed document's steps, read back through the trigger parser the engine uses. */
  private static CiPipeline stepsOf(String document) {
    return new CiEventTriggerParser().parse(".config/qits/release.yml", document).pipeline();
  }

  @Test
  public void aRefusedFoldsFirstQaStepExitsOneNamingBothValuesBeforeAnythingElseRuns(
      @TempDir Path work) throws Exception {
    CiReleaseSlots slots = slots(QA_TWO_STEPS);
    String refusal = CiReleaseConsistency.refusal(REPO, slots, deployments("bar"));
    assertNotNull(refusal);

    CiReleaseComposer.Composed refused = CiReleaseComposer.compose(REPO, slots, null, refusal);
    CiReleaseComposer.Composed plain = CiReleaseComposer.compose(REPO, slots, null);

    CiPipeline qa = stepsOf(refused.releaseRequestDocument());
    Map.Entry<Integer, String> first = runSh(work, qa.steps().get(0).script());
    assertEquals(1, first.getKey(), first.getValue());
    assertTrue(
        first.getValue().contains("qits-ci: this fold is refused: " + refusal), first.getValue());
    assertFalse(first.getValue().contains("first-step-ran"), "the declared script never ran");
    assertFalse(
        first.getValue().contains("QITS_DOMAIN"),
        "the refusal comes before the prelude, so it needs nothing a step is handed: "
            + first.getValue());

    // ONE BLOCK IN THE FIRST STEP, and nothing else moves: the later QA steps and the whole release
    // half are byte-for-byte what an unrefused fold composes.
    CiPipeline plainQa = stepsOf(plain.releaseRequestDocument());
    assertEquals(plainQa.steps().get(1).script(), qa.steps().get(1).script());
    assertEquals(plain.releaseDocument(), refused.releaseDocument());
    assertEquals(
        plain.releaseRequestDocument(),
        CiReleaseComposer.compose(REPO, slots, null, (String) null).releaseRequestDocument(),
        "no refusal composes exactly what the three-argument compose does");
  }

  @Test
  public void aSentenceCannotEscapeItsShellLine(@TempDir Path work) throws Exception {
    CiReleaseSlots slots = slots(QA_TWO_STEPS);
    String hostile = "it's `$(touch pwned)`\nexit 0";

    CiPipeline qa =
        stepsOf(CiReleaseComposer.compose(REPO, slots, null, hostile).releaseRequestDocument());
    Map.Entry<Integer, String> first = runSh(work, qa.steps().get(0).script());

    assertEquals(1, first.getKey(), first.getValue());
    assertTrue(first.getValue().contains("it's `$(touch pwned)` exit 0"), first.getValue());
    assertFalse(Files.exists(work.resolve("pwned")), "nothing in the sentence was executed");
  }

  /** {exit code, combined output} of {@code sh -c script} in {@code dir}, with only PATH set. */
  private static Map.Entry<Integer, String> runSh(Path dir, String script) throws Exception {
    ProcessBuilder builder =
        new ProcessBuilder("sh", "-c", script).directory(dir.toFile()).redirectErrorStream(true);
    builder.environment().clear();
    builder.environment().put("PATH", System.getenv("PATH"));
    Process process = builder.start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(process.waitFor(60, TimeUnit.SECONDS), "sh never exited");
    return Map.entry(process.exitValue(), output);
  }
}
