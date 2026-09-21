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
  // The five queue fields are NOT columns and never come off an entity: they are facts about the
  // queue as a whole at one instant, so only a boundary holding a forecast can supply them. Ignored
  // here rather than left unmapped so that adding a sixth is a compile-time decision about where it
  // comes from, and so this mapper stays what it says it is — a pure entity→DTO map with no clock
  // and no second read in it. CiRunDto.withQueueFacts is where they arrive.
  @Mapping(target = "queuePosition", ignore = true)
  @Mapping(target = "expectedStartInMillis", ignore = true)
  @Mapping(target = "expectedFinishInMillis", ignore = true)
  @Mapping(target = "predictionUnavailable", ignore = true)
  @Mapping(target = "ordering", ignore = true)
  // The one column that is not the shape the API speaks: the row holds the prediction as JSON text
  // and the DTO holds the list, so the codec below is the conversion. It comes off the ENTITY, which
  // is what gets every listing this field for free — nothing calling a mapper has to know it exists.
  @Mapping(target = "expectedStepDurationsMillis", source = "expectedStepDurations")
  // `phase` maps by name like every other column. It is spelled out because it was MISSING for as
  // long as the column has existed — MapStruct maps what it finds and says nothing about a target
  // component no source property matched, so the gap was invisible in the source and visible only
  // as a null in every client. A named mapping is what makes a future rename a build failure.
  @Mapping(target = "phase", source = "phase")
  // The three archetype columns, spelled out for `phase`'s reason rather than left to name matching:
  // they are provenance nothing in this service reads back, so a rename that quietly stopped copying
  // them would show up only as a null in a client, months later. They map by name today; naming them
  // is what makes that a build failure tomorrow.
  @Mapping(target = "archetypeName", source = "archetypeName")
  @Mapping(target = "archetypeConfigPath", source = "archetypeConfigPath")
  @Mapping(target = "archetypeRev", source = "archetypeRev")
  CiRunDto toDto(CiRun entity);

  CiStepDto toDto(CiStep entity);

  /**
   * A step row without its captured output — the shape a <b>listing</b> carries.
   *
   * <p><b>An explicit mapping rather than nulling the field at the call site</b>, because "which
   * listings omit the output" is a property of the surface and belongs where the surface's shapes
   * are made. A caller that built a full {@link CiStepDto} and then rewrote it would be one
   * refactor away from carrying an unbounded, repository-controlled string into a response that has
   * no affordance for rendering it, per run, for every active run on the instance.
   *
   * <p>The output is the only heavy part of a step row — it is bounded by {@code
   * qits.ci.output-max-chars} per step, not per response — and no header, rail or bolt panel
   * renders it. What a listing needs from a step is the two host-stamped instants and the index
   * they belong to, which is exactly what is left.
   */
  @Mapping(target = "output", ignore = true)
  CiStepDto toDtoWithoutOutput(CiStep entity);

  /**
   * The stored prediction as a list, or null when the column holds none — a run with no history
   * behind it, and a value that cannot be read back, which are one answer on purpose. Never throws;
   * see {@link ExpectedStepDurations}.
   */
  default List<Long> expectedStepDurationsMillis(String stored) {
    return ExpectedStepDurations.decode(stored);
  }

  /**
   * A run with its steps and live step, <b>output included</b> — the single-run read's shape, and
   * the only one that carries a step's transcript.
   */
  default CiRunDto toDto(CiRun entity, List<CiStep> steps, CiLiveStepDto live) {
    return toDto(entity).withSteps(steps.stream().map(this::toDto).toList(), live);
  }

  /**
   * A run with its steps and live step, <b>output omitted from both</b> — the shape every run
   * <em>listing</em> answers with.
   *
   * <p><b>The single read and the listings differ in exactly one thing, and it is the output.</b>
   * {@code GET /ci/api/runs/{runId}} is a person reading one build, so it carries the transcript;
   * the three listings are a client drawing many builds at once, so they carry the step
   * <em>boundaries</em> — the indices and the two host-stamped instants — and nothing else. A
   * boundary-true progress bar needs to know when each step really started and ended; it needs none
   * of what the step printed, and a listing that shipped it would multiply an unbounded,
   * repository-controlled string by however many rows the listing holds.
   *
   * <p><b>All three listings, and not just the active one.</b> Their contract was always "without
   * step output", and the output was the only reason the whole object was dropped — so keeping the
   * boundaries out of the other two bought nothing and cost the truthfulness of the bar on every
   * page but one. A finished run drawn as an entirely empty track is not a cautious answer; it is a
   * wrong-looking one, and a bar that is boundary-true on one route and not another is worse than
   * either answer applied everywhere.
   *
   * <p>{@code live} arrives already output-free — it is built at the boundary out of the in-memory
   * relay rather than out of a row, so there is nothing here to strip.
   */
  default CiRunDto toDtoWithoutStepOutput(CiRun entity, List<CiStep> steps, CiLiveStepDto live) {
    return toDto(entity).withSteps(steps.stream().map(this::toDtoWithoutOutput).toList(), live);
  }
}
