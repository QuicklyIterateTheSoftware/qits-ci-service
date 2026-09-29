package eu.wohlben.qits.ci.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.ci.control.CiRunnerPresence;
import eu.wohlben.qits.ci.dto.CiRunnerDto;
import eu.wohlben.qits.ci.dto.CiRunnerHealthcheckDto;
import eu.wohlben.qits.ci.entity.CiRunner;
import eu.wohlben.qits.ci.entity.RunnerCapabilities;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "jakarta")
public interface CiRunnerMapper {

  // `connected`, `heldRuns` and the three version facts are not columns. The first is in-memory state (CiRunnerPresence), the
  // second a count over ci_run — so the caller that holds them hands them in, and this stays a pure
  // map with no second read and no presence lookup inside it: CiRunMapper's rule for the queue facts.
  // `registered` is derived from the row rather than stored: a runner with a client is registered.
  // Every credential the row holds a handle to — the token's id and subject, the client id — is
  // deliberately NOT on the DTO at all, so there is nothing here to ignore for them.
  @Mapping(target = "id", source = "runner.id")
  @Mapping(target = "name", source = "runner.name")
  @Mapping(target = "description", source = "runner.description")
  @Mapping(target = "slots", source = "runner.slots")
  @Mapping(target = "plane", source = "runner.plane")
  @Mapping(target = "stepMemoryLimit", source = "runner.stepMemoryLimit")
  @Mapping(target = "capabilities", source = "runner.capabilities")
  @Mapping(target = "registered", expression = "java(runner.registered())")
  @Mapping(target = "connected", source = "connected")
  @Mapping(target = "heldRuns", source = "heldRuns")
  @Mapping(target = "lastSeenAt", source = "runner.lastSeenAt")
  @Mapping(target = "createdAt", source = "runner.createdAt")
  @Mapping(target = "runnerVersion", source = "versions.running")
  @Mapping(target = "targetVersion", source = "versions.target")
  @Mapping(target = "updating", source = "versions.updating")
  // The quarantine is the row's own, read straight off it; the newest health check is four columns
  // folded into one object, null while the runner has none (lastHealthcheck below).
  @Mapping(target = "quarantined", expression = "java(runner.quarantined())")
  @Mapping(target = "quarantineReason", source = "runner.quarantineReason")
  @Mapping(target = "quarantinedAt", source = "runner.quarantinedAt")
  @Mapping(target = "lastHealthcheck", expression = "java(lastHealthcheck(runner))")
  CiRunnerDto toDto(
      CiRunner runner, boolean connected, long heldRuns, CiRunnerPresence.Versions versions);

  /** The runner's newest settled health check, or null while it has none. */
  default CiRunnerHealthcheckDto lastHealthcheck(CiRunner runner) {
    if (runner.lastHealthcheckAt == null) {
      return null;
    }
    return new CiRunnerHealthcheckDto(
        runner.lastHealthcheckAt,
        runner.lastHealthcheckResult,
        runner.lastHealthcheckRunId,
        runner.lastHealthcheckDetail);
  }

  /** {@link #toDto(CiRunner, boolean, long, CiRunnerPresence.Versions)} knowing no versions. */
  default CiRunnerDto toDto(CiRunner runner, boolean connected, long heldRuns) {
    return toDto(runner, connected, heldRuns, CiRunnerPresence.Versions.UNKNOWN);
  }

  /** The stored capabilities as the object the runner sent, or null; never throws. */
  default JsonNode capabilities(String stored) {
    return RunnerCapabilities.decode(stored);
  }
}
