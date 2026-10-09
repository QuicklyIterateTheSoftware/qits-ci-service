package eu.wohlben.qits.ci.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

/**
 * A runner's newest NODE health report in full, as {@code GET /ci/api/runners/{id}/health} answers
 * it (qits-896): what the runner found when it ran its named checks on its node — or that it did not
 * answer. Read out of {@code ci_runner.node_health}. A diagnosis, never the gate: the runner's
 * standing follows the pseudo-build, which {@link CiRunnerHealthcheckDto} reports.
 *
 * @param at when it settled — answered, or timed out
 * @param ok whether every check passed; false for a report nobody answered
 * @param detail the runner's line for a person, or {@code NO_ANSWER}
 * @param requestId the {@code healthCheck} it answered, or the one that went unanswered
 * @param dataOmitted true when the checks' data was dropped because the report was too large to
 *     keep; their verdicts are all there
 * @param checks each named check, in the runner's order; empty for an unanswered request
 */
public record CiRunnerHealthDto(
    Instant at,
    boolean ok,
    String detail,
    String requestId,
    boolean dataOmitted,
    List<CheckReport> checks) {

  /**
   * One named check in full.
   *
   * @param name the check's stable name ({@code docker}, {@code nodeInventory}, {@code session},
   *     {@code buildkit}, {@code network}, {@code idRange}, {@code stepImage}, …)
   * @param ok whether it found what it looks for
   * @param detail its line for a person
   * @param data the check's own facts, a JSON object whose shape is the check's; empty when it has
   *     none or when the report's data was omitted
   */
  public record CheckReport(String name, boolean ok, String detail, JsonNode data) {}
}
