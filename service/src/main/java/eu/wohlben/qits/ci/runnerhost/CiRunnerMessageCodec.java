package eu.wohlben.qits.ci.runnerhost;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.cirunner.protocol.CiRunnerCodec;
import eu.wohlben.qits.cirunner.protocol.CiRunnerMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;

/**
 * The host's bridge between a {@link CiRunnerMessage} and its JSON text frame — {@code
 * CiDaemonMessageCodec}'s twin for the other socket. The framework-free {@link CiRunnerCodec} from
 * {@code qits-ci-runner-protocol} does the field mapping to and from a {@code Map}; this class only
 * bolts on Jackson, so the wire contract stays owned by the protocol jar and is never re-spelled
 * here. The runner binary does the symmetric job with a Vert.x {@code JsonObject}.
 *
 * <p>{@link #decode} lets the shared codec's strictness through as an exception rather than
 * softening it — an unknown or missing {@code type} and a missing required field both throw. {@link
 * CiRunnerSocket} is where that is caught: a runner one capability ahead of this host sends frames
 * this host cannot know, and one such frame must cost that frame and not the runner's socket.
 */
@ApplicationScoped
public class CiRunnerMessageCodec {

  @Inject ObjectMapper objectMapper;

  /** Serialize a message to the JSON text sent over the socket. */
  public String encode(CiRunnerMessage message) {
    try {
      return objectMapper.writeValueAsString(CiRunnerCodec.encode(message));
    } catch (Exception e) {
      throw new IllegalStateException("Failed to encode ci-runner message", e);
    }
  }

  /** Parse a received JSON text frame back into a message. */
  @SuppressWarnings("unchecked")
  public CiRunnerMessage decode(String json) {
    try {
      return CiRunnerCodec.decode(objectMapper.readValue(json, Map.class));
    } catch (Exception e) {
      throw new IllegalArgumentException("Failed to decode ci-runner message", e);
    }
  }
}
