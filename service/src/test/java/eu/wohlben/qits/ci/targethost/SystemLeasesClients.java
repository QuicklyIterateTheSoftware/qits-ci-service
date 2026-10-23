package eu.wohlben.qits.ci.targethost;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.Optional;

/**
 * A hand-wired {@link SystemLeases} for a suite outside this package — the contract tests — the way
 * {@code GitHostClients} and {@code IdpClients} hand out theirs: pointed at a url, with a fixed
 * bearer, and no CDI.
 */
public final class SystemLeasesClients {

  private SystemLeasesClients() {}

  public static SystemLeases against(String url, Optional<String> bearer) {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    return SystemLeases.against(url, mapper, bearer);
  }
}
