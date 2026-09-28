package eu.wohlben.qits.ci.api;

import eu.wohlben.qits.ci.error.CiException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;

/**
 * Maps ci's framework-free {@link CiException}s (carrying a status code) to HTTP responses — the
 * sibling of {@code ArtifactsExceptionMapper}, kept here in {@code service} because the ci module
 * carries no JAX-RS.
 */
@Provider
public class CiExceptionMapper implements ExceptionMapper<CiException> {

  @Override
  public Response toResponse(CiException exception) {
    int status = exception.statusCode();
    String message = exception.getMessage();
    if (message == null || message.isBlank()) {
      message = Response.Status.fromStatusCode(status).getReasonPhrase();
    }
    // `code` only when the refusal has one, so every existing answer keeps its exact shape.
    return Response.status(status)
        .entity(
            exception.code() == null
                ? Map.of("message", message)
                : Map.of("code", exception.code(), "message", message))
        .type(MediaType.APPLICATION_JSON)
        .build();
  }
}
