package eu.wohlben.qits.ci.error;

/**
 * 503 — the request is well-formed and the question it asks could not be asked at all, so the caller
 * should come back.
 *
 * <p>It is the sibling of {@link ConflictException} pointed the other way: that one is a definite
 * answer about the thing addressed, this one is the absence of any answer about it. A read that
 * cannot distinguish the two must never throw this — a 503 is a promise that retrying is the right
 * thing to do, and a caller that retries a real refusal forever is worse off than one that was told.
 */
public class UnavailableException extends CiException {

  public UnavailableException(String message) {
    super(503, message);
  }
}
