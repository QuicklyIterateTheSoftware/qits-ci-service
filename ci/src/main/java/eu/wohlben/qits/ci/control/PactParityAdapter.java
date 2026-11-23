package eu.wohlben.qits.ci.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * One pact framework's reading of one pact file, for the parity check (qits-2465): which provider
 * the file is with, and which operations its interactions call.
 *
 * <p>A repository names its framework in {@code contracts.mirror.framework}, and {@link #ADAPTERS}
 * is every name this qits-ci reads. Both registered frameworks write pact v4 and carry the
 * operation in the same place — {@code interactions[].comments.references.qits-call.operationId},
 * the reference qits' own recording helpers attach — so both delegate to {@link #readPactV4}; they
 * are two adapters rather than one because the declaration names the framework, not the file
 * format, and the day one of them moves the reference is the day the two readings part.
 *
 * <p>Pure: the file's text in, a {@link PactFile} out. It never throws — a file that cannot be
 * read is a {@link PactFile} carrying failures, because the check reports every problem in one
 * refusal rather than the first.
 */
public interface PactParityAdapter {

  /** Every adapter this qits-ci carries, by the framework name a declaration spells. */
  Map<String, PactParityAdapter> ADAPTERS =
      Collections.unmodifiableMap(
          new LinkedHashMap<>(
              Map.of(
                  PactJsParityAdapter.FRAMEWORK, new PactJsParityAdapter(),
                  PactJvmParityAdapter.FRAMEWORK, new PactJvmParityAdapter())));

  /** The framework name this adapter is registered under. */
  String framework();

  /**
   * Reads one pact file.
   *
   * @param path the repository-relative path, which every failure names
   * @param content the file's text
   */
  PactFile read(String path, String content);

  /**
   * What one pact file says.
   *
   * @param provider the provider's name as the file states it, or null when it states none
   * @param operationIds every operationId its interactions call, sorted
   * @param failures why the file could not be read whole, empty when it could
   */
  record PactFile(String provider, Set<String> operationIds, List<String> failures) {

    public PactFile {
      operationIds = Collections.unmodifiableSet(new TreeSet<>(operationIds));
      failures = List.copyOf(failures);
    }
  }

  /** The names of every registered framework, for a failure that has to list them. */
  static String supported() {
    return String.join(", ", new TreeSet<>(ADAPTERS.keySet()));
  }

  /**
   * The pact v4 reading every registered adapter shares: {@code provider.name}, and each
   * interaction's {@code comments.references.qits-call.operationId}. An interaction without one is
   * a failure naming its {@code description}: an operation the check cannot see is an operation it
   * cannot compare, and passing over it silently would let parity hold by omission.
   */
  static PactFile readPactV4(String path, String content) {
    JsonNode root;
    try {
      root = new ObjectMapper().readTree(content);
    } catch (Exception e) {
      return new PactFile(null, Set.of(), List.of(path + " is not JSON: " + e.getMessage()));
    }
    List<String> failures = new ArrayList<>();
    String provider = root == null ? null : text(root.path("provider").path("name"));
    if (provider == null) {
      failures.add(path + " names no provider.name");
    }
    JsonNode interactions = root == null ? null : root.get("interactions");
    Set<String> operations = new TreeSet<>();
    if (interactions == null || !interactions.isArray()) {
      failures.add(path + " has no interactions array");
    } else {
      for (JsonNode interaction : interactions) {
        String operationId =
            text(
                interaction
                    .path("comments")
                    .path("references")
                    .path("qits-call")
                    .path("operationId"));
        if (operationId == null) {
          failures.add(
              path
                  + ": interaction '"
                  + text(interaction.path("description"))
                  + "' carries no comments.references.qits-call.operationId");
        } else {
          operations.add(operationId);
        }
      }
    }
    return new PactFile(provider, operations, failures);
  }

  private static String text(JsonNode node) {
    return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
  }
}
