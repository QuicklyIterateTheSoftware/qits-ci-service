package eu.wohlben.qits.ci.persistence;

import eu.wohlben.qits.ci.entity.CiStep;
import eu.wohlben.qits.ci.entity.CiStepStatus;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.OptionalDouble;

/** Panache DAO for {@link CiStep} (keyed by its String UUID row id). */
@ApplicationScoped
public class CiStepRepository implements PanacheRepositoryBase<CiStep, String> {

  /**
   * How long one step of one pipeline has really been taking, as a percentile over its most recent
   * successful runs — the sample behind {@code ci_run.expected_step_durations}.
   *
   * <p><b>"The same step of the same pipeline" is four columns, and each of them is part of the
   * question.</b> {@code repo_id} and {@code config_path} are what a pipeline <em>is</em> — the same
   * pair the run dedupe is built on — because two trigger files in one repository are two pipelines
   * and one file in two repositories is two pipelines as well. {@code step_index} is which step,
   * since a pipeline's steps have nothing to do with each other's duration. And {@code image} is
   * what makes a sample still apply: a step that changed the container it runs in is a different
   * step doing different work, so its old rows are not evidence about the new one and this predicate
   * is what drops them. That drop is the whole of how a changed pipeline stops predicting — no
   * invalidation, no version column, just samples that stop matching.
   *
   * <p><b>Only {@code SUCCESS} rows.</b> A failed step stops where it failed, a timed-out step
   * stopped at its deadline and a skipped one never started — none of them is evidence about how
   * long the work takes, and a skipped row carries no timestamps at all. The null guards beside the
   * status are belt to that braces: legacy rows exist that were written before both instants were
   * host-stamped, and a null on either side would contribute nothing but is cheaper to exclude than
   * to reason about.
   *
   * <p><b>Bounded and newest-first, and both halves matter.</b> {@code limit} is applied to rows
   * ordered by {@code finished_at desc}, so what the percentile is taken over is the recent past
   * rather than the whole history — a pipeline that got twice as fast three months ago should stop
   * being predicted by the months before that, and a repository with ten thousand rows should not
   * pay for them on every accept.
   *
   * @return the percentile in <b>seconds</b>, or empty when the window holds no sample at all —
   *     which is what "this pipeline is new or changed" looks like from here, and what the caller
   *     turns into no prediction for the whole run.
   */
  public OptionalDouble percentileSuccessfulDurationSeconds(
      String repoId, String configPath, int stepIndex, String image, double percentile, int limit) {
    Object measured =
        getEntityManager()
            .createNativeQuery(
                // The fraction is cast rather than bound bare: percentile_cont is overloaded on
                // float8 and float8[], so postgres cannot resolve an untyped parameter there.
                "select percentile_cont(cast(?1 as double precision))"
                    + " within group (order by recent.seconds)"
                    + " from (select cast(extract(epoch from (s.finished_at - s.started_at))"
                    + "                    as double precision) as seconds"
                    + "         from ci_step s join ci_run r on r.id = s.run_id"
                    + "        where s.step_index = ?2 and s.image = ?3 and s.status = ?4"
                    + "          and s.started_at is not null and s.finished_at is not null"
                    + "          and r.repo_id = ?5 and r.config_path = ?6"
                    + "        order by s.finished_at desc"
                    + "        limit ?7) recent")
            .setParameter(1, percentile)
            .setParameter(2, stepIndex)
            .setParameter(3, image)
            .setParameter(4, CiStepStatus.SUCCESS.name())
            .setParameter(5, repoId)
            .setParameter(6, configPath)
            .setParameter(7, limit)
            .getSingleResult();
    // An empty window is one row holding null rather than no row: the aggregate always answers.
    return measured instanceof Number seconds
        ? OptionalDouble.of(seconds.doubleValue())
        : OptionalDouble.empty();
  }

  /** A run's steps in declaration order. */
  public List<CiStep> listByRunIdOrdered(String runId) {
    return list("runId = ?1 order by stepIndex", runId);
  }
}
