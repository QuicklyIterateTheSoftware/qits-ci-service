package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** {@link RetiredRegistryKeys#warningFor} on its own — plain JUnit, {@link RetiredDaemonKeysTest}'s shape. */
public class RetiredRegistryKeysTest {

  @Test
  public void aStillSetRegistryHostIsNamedWithItsValue() {
    String line =
        RetiredRegistryKeys.warningFor(
                RetiredRegistryKeys.REGISTRY_HOST_KEY, Optional.of("registry.dev.localhost:8080"))
            .orElseThrow();
    assertTrue(line.contains(RetiredRegistryKeys.REGISTRY_HOST_KEY), line);
    assertTrue(line.contains("registry.dev.localhost:8080"), line);
    assertTrue(line.contains("NO LONGER READ"), line);
  }

  @Test
  public void unsetOrBlankSaysNothing() {
    assertTrue(
        RetiredRegistryKeys.warningFor(RetiredRegistryKeys.REGISTRY_HOST_KEY, Optional.empty())
            .isEmpty());
    assertTrue(
        RetiredRegistryKeys.warningFor(RetiredRegistryKeys.RESOLVE_SWITCH_KEY, Optional.of("  "))
            .isEmpty());
  }
}
