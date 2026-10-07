package eu.wohlben.qits.ci.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import eu.wohlben.qits.ci.dto.CiBaselineAnswer;
import eu.wohlben.qits.ci.dto.CiReportBaselineDto;
import eu.wohlben.qits.ci.dto.CiReportDto;
import eu.wohlben.qits.ci.dto.CiReportHighlightDto;
import eu.wohlben.qits.ci.dto.CiReportSubmission;
import eu.wohlben.qits.ci.dto.CiReportSummaryDto;
import eu.wohlben.qits.ci.dto.CiRunReportsDto;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The JVM half of the report records' native registration: the annotation names every record the
 * report doors parse by hand or answer. The other half — that the binary really reads a stored
 * report back — is {@code CiPackagedSurfaceIT}'s, under {@code -Dnative}.
 */
class ReportWireReflectionTest {

  @Test
  void everyReportRecordIsRegistered() {
    RegisterForReflection registration =
        ReportWireReflection.class.getAnnotation(RegisterForReflection.class);
    assertNotNull(registration);
    assertEquals(
        Set.of(
            CiReportSubmission.class,
            CiReportSubmission.Baseline.class,
            CiReportHighlightDto.class,
            CiReportSummaryDto.class,
            CiReportDto.class,
            CiReportBaselineDto.class,
            CiRunReportsDto.class,
            CiBaselineAnswer.class),
        Set.of(registration.targets()));
  }
}
