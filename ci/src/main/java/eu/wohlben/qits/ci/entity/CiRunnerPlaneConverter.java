package eu.wohlben.qits.ci.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * {@code ci_runner.plane} as {@link CiRunnerPlane}, tolerant on read.
 *
 * <p>The column is a plain {@code varchar} with no check constraint and its own default is still
 * {@code 'INTERNAL'} ({@code V23__runners.sql}, which is applied and therefore never edited). The
 * enum lost that constant in qits-515, and a plain {@code @Enumerated(EnumType.STRING)} mapping
 * throws on a stored name the enum does not have — so one leftover row would cost every read of the
 * runner table. There is one plane, so every stored value reads as {@link CiRunnerPlane#EDGE},
 * whatever it says; what is written is always the constant's name.
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
