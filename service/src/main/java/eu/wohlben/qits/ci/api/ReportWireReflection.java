package eu.wohlben.qits.ci.api;

import eu.wohlben.qits.ci.dto.CiBaselineAnswer;
import eu.wohlben.qits.ci.dto.CiReportBaselineDto;
import eu.wohlben.qits.ci.dto.CiReportDto;
import eu.wohlben.qits.ci.dto.CiReportHighlightDto;
import eu.wohlben.qits.ci.dto.CiReportSubmission;
import eu.wohlben.qits.ci.dto.CiReportSummaryDto;
import eu.wohlben.qits.ci.dto.CiRunReportsDto;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Native-image registration for the release-report records, {@code bus/EventWireReflection}'s
 * pattern applied to the report doors.
 *
 * <p>Quarkus registers what a resource method <em>binds and returns</em>, and none of the reads it
 * matters for is that: the submit door reads its body as bytes and parses {@link CiReportSubmission}
 * (and its nested {@link CiReportSubmission.Baseline}) with the injected {@code ObjectMapper} only
 * after the run's binding is judged, and {@link CiReportHighlightDto} is parsed back out of the
 * {@code ci_report.highlights} text by {@code CiReportStore} through a {@code TypeReference}. Both are
 * invisible to the build-time scan, so without this a native binary answers every submit 400 and
 * every read 500 while the JVM suite stays green. The answer records are listed too, so the
 * registration does not depend on how deep the scan of a return type happens to reach.
 */
@RegisterForReflection(
    targets = {
      CiReportSubmission.class,
      CiReportSubmission.Baseline.class,
      CiReportHighlightDto.class,
      CiReportSummaryDto.class,
      CiReportDto.class,
      CiReportBaselineDto.class,
      CiRunReportsDto.class,
      CiBaselineAnswer.class
    })
public final class ReportWireReflection {

  private ReportWireReflection() {}
}
