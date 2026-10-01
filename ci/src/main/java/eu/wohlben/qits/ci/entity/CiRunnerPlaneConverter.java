package eu.wohlben.qits.ci.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * {@code ci_runner.plane} as {@link CiRunnerPlane}, tolerant on read.
 *
 * <p>The column is a plain {@code varchar} with no check constraint. {@code V28__runner_plane_normalized.sql}
 * rewrote every stored value to {@code 'EDGE'} and moved the column's own default off the retired
 * {@code 'INTERNAL'} ({@code V23__runners.sql}, applied and therefore never edited) to match. The
 * enum lost that constant in qits-515, and a plain {@code @Enumerated(EnumType.STRING)} mapping
 * throws on a stored name the enum does not have — so one leftover row, say from a restored backup
 * older than V28, would cost every read of the runner table. There is one plane, so every stored
 * value reads as {@link CiRunnerPlane#EDGE}, whatever it says; what is written is always the
 * constant's name.
 */
@Converter
public class CiRunnerPlaneConverter implements AttributeConverter<CiRunnerPlane, String> {

  @Override
  public String convertToDatabaseColumn(CiRunnerPlane plane) {
    return CiRunnerPlane.EDGE.name();
  }

  @Override
  public CiRunnerPlane convertToEntityAttribute(String stored) {
    return CiRunnerPlane.EDGE;
  }
}
