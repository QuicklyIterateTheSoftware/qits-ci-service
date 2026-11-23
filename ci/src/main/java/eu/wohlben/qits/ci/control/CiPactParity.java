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
 * <p>B is read at its newest release rather than its {@code main} because a release is the estate
 * somebody approved: a counterpart's unreleased work is not yet a promise to compare against.
 *
 * <h2>What it fails on</h2>
 *
 * <p>A missing operation is one line, {@code <provider> <operationId>: made by <X>, not by <Y> and
 * not excluded}, sorted. Besides those: A's {@code framework} naming no {@link
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
   */
  public record Side(
      String repository,
      String at,
      CiContracts contracts,
      Map<String, String> pacts,
      String unavailable) {

    public Side {
      pacts = Collections.unmodifiableMap(new LinkedHashMap<>(pacts));
    }

    public static Side read(
        String repository, String at, CiContracts contracts, Map<String, String> pacts) {
      return new Side(repository, at, contracts, pacts, null);
    }

    public static Side unavailable(String repository, String why) {
      return new Side(repository, null, null, Map.of(), why);
    }

    Mirror mirror() {
      return contracts == null ? null : contracts.mirror();
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
   * @param counterpart its {@code mirror.repository}, read at that repository's newest release
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
    if (counterpart.unavailable() != null) {
      failures.add(counterpart.unavailable());
    } else {
      Mirror theirs = counterpart.mirror();
      PactParityAdapter theirAdapter =
          theirs == null || theirs.framework() == null
              ? null
              : PactParityAdapter.ADAPTERS.get(theirs.framework());
      Map<String, Set<String>> theyMake = operations(counterpart, theirAdapter, failures);
      missing(theyMake, b, made, a, mine, missing);
      if (theirs != null && a.equals(theirs.repository())) {
        missing(made, a, theyMake, b, theirs, missing);
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
