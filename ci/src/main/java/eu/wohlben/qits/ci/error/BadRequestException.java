package eu.wohlben.qits.ci.error;

/** 400. */
public class BadRequestException extends CiException {

  public BadRequestException(String message) {
    super(400, message);
  }

  /** A 400 that names itself: {@code code} is answered beside the message. */
  public BadRequestException(String code, String message) {
    super(400, code, message);
  }
}
