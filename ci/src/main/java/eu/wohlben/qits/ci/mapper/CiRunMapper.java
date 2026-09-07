package eu.wohlben.qits.ci.mapper;

import eu.wohlben.qits.ci.dto.CiLiveStepDto;
import eu.wohlben.qits.ci.dto.CiRunDto;
import eu.wohlben.qits.ci.dto.CiStepDto;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiStep;
import eu.wohlben.qits.ci.entity.ExpectedStepDurations;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "jakarta")
public interface CiRunMapper {

  // Steps are keyed by runId, not a JPA relation — the boundary attaches them explicitly (single
  // -run endpoint only; listings keep steps null). `live` is not persisted at all: it is read from
  // the in-memory relay, which only the boundary can see.
  @Mapping(target = "steps", ignore = true)
  @Mapping(target = "live", ignore = true)
  // The one column that is not the shape the API speaks: the row holds the prediction as JSON text
  // and the DTO holds the list, so the codec below is the conversion. It comes off the ENTITY, which
  // is what gets every listing this field for free — nothing calling a mapper has to know it exists.
  @Mapping(target = "expectedStepDurationsMillis", source = "expectedStepDurations")
  CiRunDto toDto(CiRun entity);

  CiStepDto toDto(CiStep entity);

  /**
   * The stored prediction as a list, or null when the column holds none — a run with no history
   * behind it, and a value that cannot be read back, which are one answer on purpose. Never throws;
   * see {@link ExpectedStepDurations}.
   */
  default List<Long> expectedStepDurationsMillis(String stored) {
    return ExpectedStepDurations.decode(stored);
  }

  default CiRunDto toDto(CiRun entity, List<CiStep> steps, CiLiveStepDto live) {
    CiRunDto bare = toDto(entity);
    return new CiRunDto(
        bare.id(),
        bare.repoId(),
        bare.projectId(),
        bare.repoName(),
        bare.branch(),
        bare.commitSha(),
        bare.gating(),
        bare.status(),
        bare.createdAt(),
        bare.startedAt(),
        bare.finishedAt(),
        bare.cancellationReason(),
        bare.supersededByRunId(),
        bare.daemonVersion(),
        bare.triggerType(),
        bare.triggerEventId(),
        bare.triggerEventName(),
        bare.releaseRequestId(),
        bare.retryOfRunId(),
        bare.configPath(),
        bare.priority(),
        bare.expectedStepDurationsMillis(),
        steps.stream().map(this::toDto).toList(),
        live);
  }
}
