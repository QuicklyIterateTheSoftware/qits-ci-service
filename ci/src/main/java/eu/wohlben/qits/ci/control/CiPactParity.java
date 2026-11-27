package eu.wohlben.qits.ci.control;

import eu.wohlben.qits.ci.control.CiContracts.Mirror;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The pact parity check (qits-2465): two consumers that declare each other in {@code
 * contracts.mirror} must record the same operations against every provider, or say why not.
 *
 * <h2>The rule, and which direction is whose</h2>
 *
 * <p>"X's operations must be made by Y" is checked <b>only when Y declares {@code mirror.repository:
 * X}</b> — a repository answers for what it makes, never for what its counterpart makes. For every
 * provider, every operationId X's pacts record must be recorded by Y's pacts too, or be excluded in
 * Y's {@code mirror.excluded} (the whole provider, or that operationId).
 *
 * <p>In the gated repository A's QA run, with B its {@code mirror.repository}:
 *
 * <ul>
 *   <li><b>B's operations must be made by A</b> always — A declares B, by definition, and A's
 *       exclusions are read at the fold, the commit being gated.
 *   <li><b>A's operations must be made by B</b> only when B's release.yml <em>at its newest
 *       release</em> declares {@code mirror.repository: A}; otherwise the direction is skipped, and
 *       {@link Verdict#note} says so.
 * </ul>
 *
 * <h2>Where B is read, and why it is two places</h2>
 *
 * <p><b>What B demands</b> — "B's operations must be made by A" — is read at B's newest release
 * alone, never at its {@code main}: a release is the estate somebody approved, and a counterpart's
 * unreleased work is not yet a promise A could be refused for missing. Were B's open release
 * requests counted here, B's work in flight would refuse every unrelated release of A.
 *
 * <p><b>What B offers</b> — the set A's operations are looked up in — is B's newest release
 * <em>and</em> the folds of its open release requests ({@link Side#openFolds}, qits-2604). Without
 * them two mirrors could never gain an operation: A's request making it is refused because B's
 * release lacks it, B's request making it is refused because A's release lacks it, and neither
 * release ever comes (seen live on qits-2490 with {@code listArtifactVersions}). An open fold is
 * extra evidence, not a promise: a fold file that does not parse, or that the fold does not carry,
 * adds nothing and fails nothing. A fold is read at the pact paths its own {@code release.yml}
 * declares as well as the release's (qits-2732), so a request that adds a provider no release
 * declares yet is seen too. B's {@code mirror} declaration and its exclusions stay read at the
 * release, so whether the direction is checked at all, and what is excused, is still B's approved
 * word.
 *
 * <h2>What it fails on</h2>
 *
 * <p>A missing operation is one line, {@code <provider> <operationId>: made by <X>, not by <Y> and
 * not excluded}, sorted, and then one line naming where B was looked for — the release and the open
 * release requests read — and what to do about it. Besides those: A's {@code framework} naming no {@link
 * PactParityAdapter#ADAPTERS adapter} (named with the supported list — a check-time failure, not a
 * parse error, because a parse error records no run at all), a declared pact file the read rev does
 * not carry, an interaction with no operationId, and a counterpart that cannot be compared at all —
 * no release, no {@code release.yml} at it, or one that does not parse ({@link
 * Side#unavailable}). B's own {@code framework} is not judged here: its files are read with its
 * adapter when it names one and as plain pact v4 otherwise, and a bad name is B's QA's to refuse.
 *
 * <p>A pure function, like {@link CiReleaseConsistency}: the caller reads the files ({@code
 * CiPactParityInputs}) and a refusal is composed into the QA document exactly as that check's is.
 */
public final class CiPactParity {

  private CiPactParity() {}

  /**
   * One consumer as the check sees it.
   *
   * @param repository the consumer's repository name, which names its pact files
   * @param at where it was read, for a person — {@code the fold <sha>}, {@code release <version>}
   * @param contracts its {@code contracts:} declaration at that rev, or null when it declares none
   * @param pacts every pact file its {@code contracts.pacts} declares, by path, in declared order;
   *     a null content is a file the rev does not carry
   * @param unavailable why this side cannot be compared at all, or null when it can
   * @param openFolds the counterpart's open release requests, each read at its fold — what it
   *     offers beyond its release (qits-2604). Null when they were not read at all: the gated side,
   *     and a counterpart on a deployment with no qits-projects to list them
   */
  public record Side(
      String repository,
      String at,
      CiContracts contracts,
      Map<String, String> pacts,
      String unavailable,
      List<OpenFold> openFolds) {

    public Side {
      pacts = Collections.unmodifiableMap(new LinkedHashMap<>(pacts));
      openFolds = openFolds == null ? null : List.copyOf(openFolds);
    }

    public static Side read(
        String repository, String at, CiContracts contracts, Map<String, String> pacts) {
      return new Side(repository, at, contracts, pacts, null, null);
    }

    /** A counterpart read at its release and at the folds of its open release requests. */
    public static Side read(
        String repository,
        String at,
        CiContracts contracts,
        Map<String, String> pacts,
        List<OpenFold> openFolds) {
      return new Side(repository, at, contracts, pacts, null, openFolds);
    }

    public static Side unavailable(String repository, String why) {
      return new Side(repository, null, null, Map.of(), why, null);
    }

    Mirror mirror() {
      return contracts == null ? null : contracts.mirror();
    }
  }

  /**
   * One open release request of the counterpart, read at its fold (qits-2604).
   *
   * @param label what a person calls the request — its qualified id
   * @param pacts the pact files the fold's own {@code release.yml} declares, together with the
   *     release's (qits-2732), as the fold carries them, by path; a file the fold does not carry is
   *     simply absent
   */
  public record OpenFold(String label, Map<String, String> pacts) {

    public OpenFold {
      pacts = Collections.unmodifiableMap(new LinkedHashMap<>(pacts));
    }
  }

  /**
   * The check's answer.
   *
   * @param refusal the sentence to refuse the fold's QA with — a heading line, then one line per
   *     problem — or null when parity holds
   * @param note what was not checked and why, or null; on a refusal it is the refusal's last line
   *     already, so a caller logs it only when there is no refusal
   */
  public record Verdict(String refusal, String note) {}

  /**
   * Every pact file {@code contracts} declares for {@code repository}: one per provider under
   * {@code contracts.pacts}, named {@code pacts/<repository>_<provider>.json}.
   */
  public static List<String> pactPaths(String repository, CiContracts contracts) {
    if (contracts == null) {
      return List.of();
    }
    return contracts.pacts().keySet().stream()
        .map(provider -> CiContracts.pactPath(repository, provider))
        .toList();
  }

  /**
   * Whether the gated side and its counterpart are in parity.
   *
   * @param gated the repository the fold is of, read at the fold; nothing is checked unless it
   *     declares {@code contracts.mirror}
   * @param counterpart its {@code mirror.repository}, read at that repository's newest release and,
   *     when they could be listed, at the folds of its open release requests
   */
  public static Verdict check(Side gated, Side counterpart) {
    Mirror mine = gated.mirror();
    if (mine == null) {
      return new Verdict(null, null);
    }
    String a = gated.repository();
    String b = mine.repository();
    String heading = "pact parity between " + a + " and " + b + " fails (contracts.mirror):";
    PactParityAdapter adapter =
        mine.framework() == null ? null : PactParityAdapter.ADAPTERS.get(mine.framework());
    if (adapter == null) {
      return new Verdict(
          heading
              + "\n"
              + a
              + "'s contracts.mirror.framework is "
              + (mine.framework() == null ? "missing" : "'" + mine.framework() + "'")
              + " — this qits-ci reads "
              + PactParityAdapter.supported(),
          null);
    }
    List<String> failures = new ArrayList<>();
    Map<String, Set<String>> made = operations(gated, adapter, failures);
    List<String> missing = new ArrayList<>();
    String note = null;
    String lookedFor = null;
    if (counterpart.unavailable() != null) {
      failures.add(counterpart.unavailable());
    } else {
      Mirror theirs = counterpart.mirror();
      PactParityAdapter theirAdapter =
          theirs == null || theirs.framework() == null
              ? null
              : PactParityAdapter.ADAPTERS.get(theirs.framework());
      // What B demands is its release alone; what it offers is its release and its open folds.
      Map<String, Set<String>> theyDemand = operations(counterpart, theirAdapter, failures);
      missing(theyDemand, b, made, a, mine, missing);
      if (theirs != null && a.equals(theirs.repository())) {
        List<String> notOffered = new ArrayList<>();
        missing(made, a, offered(counterpart, theirAdapter, theyDemand), b, theirs, notOffered);
        missing.addAll(notOffered);
        if (!notOffered.isEmpty()) {
          lookedFor = lookedFor(counterpart);
        }
      } else {
        note =
            b
                + " at "
                + counterpart.at()
                + " declares no contracts.mirror naming "
                + a
                + ", so whether "
                + b
                + " makes "
                + a
                + "'s operations is not checked";
      }
    }
    if (failures.isEmpty() && missing.isEmpty()) {
      return new Verdict(null, note);
    }
    List<String> lines = new ArrayList<>();
    lines.add(heading);
    lines.addAll(failures);
    lines.addAll(new TreeSet<>(missing));
    if (lookedFor != null) {
      lines.add(lookedFor);
    }
    if (note != null) {
      lines.add(note);
    }
    return new Verdict(String.join("\n", lines), note);
  }

  /**
   * One side's operations by provider, as its pact files state them. A file the rev does not carry
   * and a file its adapter could not read whole are failures; what could be read still counts.
   *
   * @param adapter the side's adapter, or null to read plain pact v4
   */
  private static Map<String, Set<String>> operations(
      Side side, PactParityAdapter adapter, List<String> failures) {
    Map<String, Set<String>> byProvider = new TreeMap<>();
    for (Map.Entry<String, String> pact : side.pacts().entrySet()) {
      String path = pact.getKey();
      if (pact.getValue() == null) {
        failures.add(
            side.repository()
                + " declares "
                + path
                + " under contracts.pacts, but "
                + side.at()
                + " carries no such file");
        continue;
      }
      PactParityAdapter.PactFile file =
          adapter == null
              ? PactParityAdapter.readPactV4(path, pact.getValue())
              : adapter.read(path, pact.getValue());
      file.failures().forEach(failure -> failures.add(side.repository() + "'s " + failure));
      if (file.provider() != null) {
        byProvider.computeIfAbsent(file.provider(), p -> new TreeSet<>()).addAll(file.operationIds());
      }
    }
    return byProvider;
  }

  /**
   * What the counterpart offers: its release's operations and every open fold's, by provider. A
   * fold file the adapter cannot read whole still contributes what it could read, and its failures
   * are dropped — an open fold is evidence, never a reason to refuse.
   */
  private static Map<String, Set<String>> offered(
      Side counterpart, PactParityAdapter adapter, Map<String, Set<String>> released) {
    Map<String, Set<String>> byProvider = new TreeMap<>();
    released.forEach((provider, ids) -> byProvider.put(provider, new TreeSet<>(ids)));
    if (counterpart.openFolds() == null) {
      return byProvider;
    }
    for (OpenFold fold : counterpart.openFolds()) {
      for (Map.Entry<String, String> pact : fold.pacts().entrySet()) {
        if (pact.getValue() == null) {
          continue;
        }
        // Its failures are not collected: an adapter answers a bad file as failures, never throws.
        PactParityAdapter.PactFile file =
            adapter == null
                ? PactParityAdapter.readPactV4(pact.getKey(), pact.getValue())
                : adapter.read(pact.getKey(), pact.getValue());
        if (file.provider() != null) {
          byProvider
              .computeIfAbsent(file.provider(), p -> new TreeSet<>())
              .addAll(file.operationIds());
        }
      }
    }
    return byProvider;
  }

  /**
   * The line after a refusal's missing operations that the counterpart was asked to make: where it
   * was looked for, and the way out — an open request making the operation, then a retry.
   */
  private static String lookedFor(Side counterpart) {
    String b = counterpart.repository();
    if (counterpart.openFolds() == null) {
      return b
          + " was read at "
          + counterpart.at()
          + " only, as this qits-ci has no qits-projects to list its open release requests; release"
          + " "
          + b
          + " with the operation and retry this run with qits ci retry";
    }
    String requests =
        counterpart.openFolds().isEmpty()
            ? "none"
            : String.join(", ", counterpart.openFolds().stream().map(OpenFold::label).toList());
    return b
        + " was read at "
        + counterpart.at()
        + " and open release requests "
        + requests
        + "; open a release request on "
        + b
        + " that makes the operation and retry this run with qits ci retry";
  }

  /** Every operation {@code x} makes that {@code y} neither makes nor excludes, as one line each. */
  private static void missing(
      Map<String, Set<String>> xMakes,
      String x,
      Map<String, Set<String>> yMakes,
      String y,
      Mirror yDeclares,
      List<String> out) {
    for (Map.Entry<String, Set<String>> provider : xMakes.entrySet()) {
      Set<String> made = yMakes.getOrDefault(provider.getKey(), Set.of());
      for (String operationId : provider.getValue()) {
        if (!made.contains(operationId) && !yDeclares.excludes(provider.getKey(), operationId)) {
          out.add(
              provider.getKey()
                  + " "
                  + operationId
                  + ": made by "
                  + x
                  + ", not by "
                  + y
                  + " and not excluded");
        }
      }
    }
  }
}
