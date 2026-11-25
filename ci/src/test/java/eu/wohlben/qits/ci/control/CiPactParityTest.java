package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The pact parity check (qits-2465): both directions, the skip when the counterpart does not
 * declare the gated repository, both kinds of exclusion, and the failures that are not a missing
 * operation — an unknown framework, an interaction with no operationId, a declared pact the rev
 * does not carry, a counterpart with no release.
 *
 * <p>Plain JUnit and no fakes, like {@link CiReleaseConsistencyTest}: {@link CiPactParity} is a pure
 * function of the two sides' files.
 */
public class CiPactParityTest {

  private static final String LANDING = "qits-landing-app";
  private static final String CLI = "qits-platform-access-monorepo";

  private final CiReleaseSlotParser parser = new CiReleaseSlotParser();

  private CiContracts contracts(String yaml) {
    return parser.parse(CiReleaseSlotParser.CONFIG_PATH, yaml).contracts();
  }

  /**
   * A {@code contracts:} section pacting with {@code providers}, plus a {@code mirror} block. With
   * no provider it declares a routes site instead, the section's other tree a consumer may have.
   */
  private static String declaration(String mirror, String... providers) {
    StringBuilder yaml =
        new StringBuilder(
            "contracts:\n  application: x\n"
                + (providers.length == 0 ? "  routes: { from: freezes/ }\n" : "  pacts:\n"));
    for (String provider : providers) {
      yaml.append("    ").append(provider).append(": { packages: [maven] }\n");
    }
    return yaml + (mirror == null ? "" : mirror);
  }

  /** A pact v4 file with {@code provider}, one interaction per operationId. */
  private static String pact(String provider, String... operationIds) {
    String interactions =
        Arrays.stream(operationIds)
            .map(
                id ->
                    "{\"description\":\"calls "
                        + id
                        + "\",\"comments\":{\"references\":{\"qits-call\":{\"app\":\""
                        + provider
                        + "\",\"operationId\":\""
                        + id
                        + "\"}}}}")
            .collect(Collectors.joining(","));
    return "{\"consumer\":{\"name\":\"c\"},\"provider\":{\"name\":\""
        + provider
        + "\"},\"interactions\":["
        + interactions
        + "],\"metadata\":{\"pactSpecification\":{\"version\":\"4.0\"}}}";
  }

  /** One side: its declaration, and a pact per provider as {@code provider -> operationIds}. */
  private CiPactParity.Side side(
      String repository, String at, String mirror, Map<String, List<String>> made) {
    CiContracts contracts =
        contracts(declaration(mirror, made.keySet().toArray(String[]::new)));
    Map<String, String> pacts = new LinkedHashMap<>();
    made.forEach(
        (provider, ids) ->
            pacts.put(
                CiContracts.pactPath(repository, provider),
                pact(provider, ids.toArray(String[]::new))));
    return CiPactParity.Side.read(repository, at, contracts, pacts);
  }

  private static String mirrorOf(String repository, String framework, String excluded) {
    return "  mirror:\n    repository: "
        + repository
        + "\n    framework: "
        + framework
        + "\n"
        + (excluded == null ? "" : "    excluded:\n" + excluded);
  }

  private CiPactParity.Side landing(String excluded, Map<String, List<String>> made) {
    return side(LANDING, "the fold eeee", mirrorOf(CLI, "pact-js", excluded), made);
  }

  private CiPactParity.Side cli(String mirror, Map<String, List<String>> made) {
    return side(CLI, "release 2026.1123.1", mirror, made);
  }

  // --- the rule ---------------------------------------------------------------------------------

  @Test
  public void identicalOperationsPass() {
    Map<String, List<String>> same = Map.of("qits-ci-service", List.of("listRuns", "getRun"));
    CiPactParity.Verdict verdict =
        CiPactParity.check(
            landing(null, same), cli(mirrorOf(LANDING, "pact-jvm", null), same));
    assertNull(verdict.refusal());
    assertNull(verdict.note(), "both directions were checked");
  }

  @Test
  public void bothDirectionsFailOneLinePerMissingOperationSorted() {
    CiPactParity.Verdict verdict =
        CiPactParity.check(
            landing(
                null,
                Map.of(
                    "qits-ci-service", List.of("listRuns", "getRun"),
                    "qits-system-service", List.of("listTargets"))),
            cli(
                mirrorOf(LANDING, "pact-jvm", null),
                Map.of(
                    "qits-ci-service", List.of("listRuns", "retryRun"),
                    "qits-idp-service", List.of("token"))));

    assertEquals(
        String.join(
            "\n",
            "pact parity between qits-landing-app and qits-platform-access-monorepo fails"
                + " (contracts.mirror):",
            "qits-ci-service getRun: made by qits-landing-app, not by qits-platform-access-monorepo"
                + " and not excluded",
            "qits-ci-service retryRun: made by qits-platform-access-monorepo, not by"
                + " qits-landing-app and not excluded",
            "qits-idp-service token: made by qits-platform-access-monorepo, not by qits-landing-app"
                + " and not excluded",
            "qits-system-service listTargets: made by qits-landing-app, not by"
                + " qits-platform-access-monorepo and not excluded",
            // qits-2604: where the counterpart was looked for; this side lists no open requests.
            "qits-platform-access-monorepo was read at release 2026.1123.1 only, as this qits-ci"
                + " has no qits-projects to list its open release requests; release"
                + " qits-platform-access-monorepo with the operation and retry this run with qits"
                + " ci retry"),
        verdict.refusal());
  }

  @Test
  public void theGatedSidesOperationsAreNotCheckedWhenTheCounterpartDoesNotDeclareIt() {
    // The CLI's released file declares no mirror: only "the CLI's operations must be made by the
    // landing app" is the landing app's to answer, and a passing check says what it skipped.
    CiPactParity.Verdict passing =
        CiPactParity.check(
            landing(null, Map.of("qits-ci-service", List.of("listRuns", "getRun"))),
            cli(null, Map.of("qits-ci-service", List.of("listRuns"))));
    assertNull(passing.refusal(), "getRun is the landing app's own; the CLI never promised it");
    assertEquals(
        "qits-platform-access-monorepo at release 2026.1123.1 declares no contracts.mirror naming"
            + " qits-landing-app, so whether qits-platform-access-monorepo makes qits-landing-app's"
            + " operations is not checked",
        passing.note());

    // A counterpart mirroring somebody else is the same skip.
    CiPactParity.Verdict failing =
        CiPactParity.check(
            landing(null, Map.of("qits-ci-service", List.of("listRuns"))),
            cli(
                mirrorOf("qits-other-app", "pact-jvm", null),
                Map.of("qits-ci-service", List.of("listRuns", "retryRun"))));
    List<String> lines = List.of(failing.refusal().split("\n"));
    assertEquals(3, lines.size(), failing.refusal());
    assertTrue(lines.get(1).startsWith("qits-ci-service retryRun: made by " + CLI), lines.get(1));
    assertEquals(failing.note(), lines.get(2), "on a refusal the skip is its last line");
  }

  @Test
  public void providerWideAndPerOperationExclusionsPass() {
    CiPactParity.Verdict verdict =
        CiPactParity.check(
            landing(
                "      qits-idp-service: \"the browser signs in through the edge\"\n"
                    + "      qits-ci-service:\n"
                    + "        retryRun: \"retries are a terminal habit\"\n",
                Map.of("qits-ci-service", List.of("listRuns"))),
            cli(
                mirrorOf(
                    LANDING,
                    "pact-jvm",
                    "      qits-system-service:\n"
                        + "        listTargets: \"the CLI shows no targets\"\n"),
                Map.of(
                    "qits-ci-service", List.of("listRuns", "retryRun"),
                    "qits-idp-service", List.of("token", "introspect"))));
    // The landing app's own listTargets would need the CLI's exclusion; it makes none here, so the
    // exclusion is also shown to be harmless when unused.
    assertNull(verdict.refusal(), verdict.refusal());
  }

  // --- failures that are not a missing operation ----------------------------------------------

  @Test
  public void anUnknownFrameworkFailsNamingTheSupportedOnes() {
    CiPactParity.Verdict verdict =
        CiPactParity.check(
            side(
                LANDING,
                "the fold eeee",
                mirrorOf(CLI, "pact-python", null),
                Map.of("qits-ci-service", List.of("listRuns"))),
            cli(null, Map.of()));
    assertEquals(
        "pact parity between qits-landing-app and qits-platform-access-monorepo fails"
            + " (contracts.mirror):\n"
            + "qits-landing-app's contracts.mirror.framework is 'pact-python' — this qits-ci reads"
            + " pact-js, pact-jvm",
        verdict.refusal());

    CiPactParity.Verdict undeclared =
        CiPactParity.check(
            side(LANDING, "the fold eeee", "  mirror: { repository: " + CLI + " }\n", Map.of()),
            cli(null, Map.of()));
    assertTrue(
        undeclared.refusal().contains("contracts.mirror.framework is missing"),
        undeclared.refusal());
  }

  @Test
  public void anInteractionWithoutAnOperationIdFailsNamingItsDescription() {
    CiContracts contracts =
        contracts(declaration(mirrorOf(CLI, "pact-js", null), "qits-ci-service"));
    String path = CiContracts.pactPath(LANDING, "qits-ci-service");
    String file =
        "{\"provider\":{\"name\":\"qits-ci-service\"},\"interactions\":["
            + "{\"description\":\"show-active-builds: list\",\"comments\":{}}]}";
    CiPactParity.Verdict verdict =
        CiPactParity.check(
            CiPactParity.Side.read(LANDING, "the fold eeee", contracts, Map.of(path, file)),
            cli(mirrorOf(LANDING, "pact-jvm", null), Map.of()));
    assertEquals(
        "pact parity between qits-landing-app and qits-platform-access-monorepo fails"
            + " (contracts.mirror):\n"
            + "qits-landing-app's pacts/qits-landing-app_qits-ci-service.json: interaction"
            + " 'show-active-builds: list' carries no comments.references.qits-call.operationId",
        verdict.refusal());
  }

  @Test
  public void aDeclaredPactTheRevDoesNotCarryFailsNamingTheFile() {
    CiContracts contracts =
        contracts(declaration(mirrorOf(LANDING, "pact-jvm", null), "qits-ci-service"));
    Map<String, String> absent = new LinkedHashMap<>();
    absent.put(CiContracts.pactPath(CLI, "qits-ci-service"), null);
    CiPactParity.Verdict verdict =
        CiPactParity.check(
            landing(null, Map.of()),
            CiPactParity.Side.read(CLI, "release 2026.1123.1", contracts, absent));
    assertTrue(
        verdict
            .refusal()
            .contains(
                "qits-platform-access-monorepo declares"
                    + " pacts/qits-platform-access-monorepo_qits-ci-service.json under"
                    + " contracts.pacts, but release 2026.1123.1 carries no such file"),
        verdict.refusal());
  }

  @Test
  public void aCounterpartThatCannotBeComparedFails() {
    CiPactParity.Verdict verdict =
        CiPactParity.check(
            landing(null, Map.of("qits-ci-service", List.of("listRuns"))),
            CiPactParity.Side.unavailable(CLI, CLI + " has no release this qits-ci recorded"));
    assertEquals(
        "pact parity between qits-landing-app and qits-platform-access-monorepo fails"
            + " (contracts.mirror):\n"
            + "qits-platform-access-monorepo has no release this qits-ci recorded",
        verdict.refusal());
  }

  @Test
  public void withoutAMirrorNothingIsChecked() {
    CiPactParity.Side plain =
        side(LANDING, "the fold eeee", null, Map.of("qits-ci-service", List.of("listRuns")));
    CiPactParity.Verdict verdict =
        CiPactParity.check(plain, CiPactParity.Side.unavailable(CLI, "never read"));
    assertNull(verdict.refusal());
    assertNull(verdict.note());
  }

  // --- qits-2604: the counterpart offers its open release requests' folds too -----------------

  /** The CLI at its release, plus open folds, each a {@code label -> provider -> operationIds}. */
  private CiPactParity.Side cliWithFolds(
      Map<String, List<String>> released, Map<String, Map<String, List<String>>> folds) {
    CiPactParity.Side release = cli(mirrorOf(LANDING, "pact-jvm", null), released);
    List<CiPactParity.OpenFold> open =
        folds.entrySet().stream()
            .map(
                fold -> {
                  Map<String, String> pacts = new LinkedHashMap<>();
                  fold.getValue()
                      .forEach(
                          (provider, ids) ->
                              pacts.put(
                                  CiContracts.pactPath(CLI, provider),
                                  pact(provider, ids.toArray(String[]::new))));
                  return new CiPactParity.OpenFold(fold.getKey(), pacts);
                })
            .toList();
    return CiPactParity.Side.read(
        release.repository(), release.at(), release.contracts(), release.pacts(), open);
  }

  @Test
  public void anOperationAnOpenFoldOfTheCounterpartMakesIsNotRefused() {
    // qits-2490's deadlock: both gain listArtifactVersions, neither has released it.
    CiPactParity.Verdict verdict =
        CiPactParity.check(
            landing(null, Map.of("qits-ci-service", List.of("listRuns", "listArtifactVersions"))),
            cliWithFolds(
                Map.of("qits-ci-service", List.of("listRuns")),
                Map.of(
                    "qits-platform-access-monorepo-rr-12",
                    Map.of("qits-ci-service", List.of("listRuns", "listArtifactVersions")))));
    assertNull(verdict.refusal(), verdict.refusal());
    assertNull(verdict.note());
  }

  @Test
  public void anOpenFoldsOwnNewOperationIsNotDemandedOfTheGatedSide() {
    // The CLI's unreleased work is no promise: the landing app's release is not refused for it.
    CiPactParity.Verdict verdict =
        CiPactParity.check(
            landing(null, Map.of("qits-ci-service", List.of("listRuns"))),
            cliWithFolds(
                Map.of("qits-ci-service", List.of("listRuns")),
                Map.of(
                    "qits-platform-access-monorepo-rr-12",
                    Map.of(
                        "qits-ci-service", List.of("listRuns", "retryRun"),
                        "qits-idp-service", List.of("token")))));
    assertNull(verdict.refusal(), verdict.refusal());
  }

  @Test
  public void anOperationNeitherMakesIsRefusedNamingWhereTheCounterpartWasRead() {
    // In the order the listing answered them, which the line keeps.
    Map<String, Map<String, List<String>>> folds = new LinkedHashMap<>();
    folds.put("qits-platform-access-monorepo-rr-12", Map.of("qits-ci-service", List.of("listRuns")));
    folds.put("qits-platform-access-monorepo-rr-13", Map.of());
    CiPactParity.Verdict verdict =
        CiPactParity.check(
            landing(null, Map.of("qits-ci-service", List.of("listRuns", "getRun"))),
            cliWithFolds(Map.of("qits-ci-service", List.of("listRuns")), folds));
    assertEquals(
        String.join(
            "\n",
            "pact parity between qits-landing-app and qits-platform-access-monorepo fails"
                + " (contracts.mirror):",
            "qits-ci-service getRun: made by qits-landing-app, not by qits-platform-access-monorepo"
                + " and not excluded",
            "qits-platform-access-monorepo was read at release 2026.1123.1 and open release"
                + " requests qits-platform-access-monorepo-rr-12, qits-platform-access-monorepo-rr-13;"
                + " open a release request on qits-platform-access-monorepo that makes the operation"
                + " and retry this run with qits ci retry"),
        verdict.refusal());

    CiPactParity.Verdict none =
        CiPactParity.check(
            landing(null, Map.of("qits-ci-service", List.of("getRun"))),
            cliWithFolds(Map.of("qits-ci-service", List.of("listRuns")), Map.of()));
    assertTrue(
        none.refusal().endsWith(" and open release requests none; open a release request on " + CLI
            + " that makes the operation and retry this run with qits ci retry"),
        none.refusal());
  }

  @Test
  public void anOpenFoldThatDoesNotParseOffersNothingAndFailsNothing() {
    CiPactParity.Side release =
        cli(mirrorOf(LANDING, "pact-jvm", null), Map.of("qits-ci-service", List.of("listRuns")));
    CiPactParity.Side counterpart =
        CiPactParity.Side.read(
            release.repository(),
            release.at(),
            release.contracts(),
            release.pacts(),
            List.of(
                new CiPactParity.OpenFold(
                    "qits-platform-access-monorepo-rr-12",
                    Map.of(CiContracts.pactPath(CLI, "qits-ci-service"), "{not json"))));
    CiPactParity.Verdict parity =
        CiPactParity.check(
            landing(null, Map.of("qits-ci-service", List.of("listRuns"))), counterpart);
    assertNull(parity.refusal(), parity.refusal());

    CiPactParity.Verdict missing =
        CiPactParity.check(
            landing(null, Map.of("qits-ci-service", List.of("listRuns", "getRun"))), counterpart);
    assertEquals(3, missing.refusal().split("\n").length, missing.refusal());
    assertTrue(missing.refusal().contains("getRun: made by"), missing.refusal());
  }

  @Test
  public void aMissingOperationOfTheGatedSideNamesNoReleaseRequests() {
    // B's own operation missing from A is A's to fix: no line sends anybody to B.
    CiPactParity.Verdict verdict =
        CiPactParity.check(
            landing(null, Map.of("qits-ci-service", List.of("listRuns"))),
            cliWithFolds(Map.of("qits-ci-service", List.of("listRuns", "retryRun")), Map.of()));
    assertEquals(2, verdict.refusal().split("\n").length, verdict.refusal());
  }
}
