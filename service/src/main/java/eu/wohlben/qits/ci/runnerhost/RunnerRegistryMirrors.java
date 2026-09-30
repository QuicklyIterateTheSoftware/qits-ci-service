package eu.wohlben.qits.ci.runnerhost;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * <b>What a runner's builder must rewrite</b>: every spelling of the platform's registry and
 * mirror that the estate's committed Dockerfiles use, mapped to the PUBLIC name of the same store —
 * the {@code Ack}'s {@code registryMirrors} (qits-466's companion fix, found live).
 *
 * <p><b>Why a runner needs telling.</b> The estate's Dockerfiles say {@code FROM
 * mirror.dev.localhost:8080/…} and {@code FROM registry.dev.localhost:8080/…} — the platform's
 * MACHINE spellings, which resolve only in the platform host's own namespace. A runner's builder is
 * on another machine and is configured with none of them, so a build step there died on its first
 * {@code FROM}. A step image is already moved for it
 * ({@link StepAddressPlane.ImageRegistries#rewrite}), but an image a BUILD names is inside the build,
 * where only the builder's own mirror table reaches. So the host hands the runner that table.
 *
 * <p><b>The spellings are the ones {@link StepAddressPlane.ImageRegistries} already knows</b>
 * ({@link StepContainerSettings#imageSpellings}) — {@code qits.artifacts.registry-host}, the machine
 * spellings a committed file uses ({@code qits.ci.runner.registry-mirrors.registry-hosts} and {@code
 * .mirror-hosts}) and the two qits-net aliases of this environment — each to {@code
 * registry.qits.<domain>} or {@code mirror.qits.<domain>}. The three bare upstreams the platform's
 * mirror serves under its own namespaces — {@code docker.io} under {@code /hub}, {@code quay.io}
 * under {@code /quay}, {@code registry.access.redhat.com} under {@code /redhat} ({@code .upstreams})
 * — go to the public mirror under the same paths, so an unqualified {@code FROM quay.io/…} is pulled
 * through the platform's mirror rather than dialling upstream from the runner's machine.
 *
 * <p><b>A qits-ci that knows no public domain sends none</b> (null, "not sent"): there is no public
 * name to map to, and such a runner's steps are refused {@code EDGE_PLANE_UNCONFIGURED} anyway.
 */
@ApplicationScoped
public class RunnerRegistryMirrors {

  /** For the spellings: {@link StepContainerSettings#imageSpellings}. */
  @Inject StepContainerSettings launcher;

  /** For the public names: the same {@code edgeOrigins} a step is told. */
  @Inject RunnerAddresses addresses;

  /** {@code <upstream host>=<path on the mirror>} pairs. */
  @ConfigProperty(name = "qits.ci.runner.registry-mirrors.upstreams")
  Optional<List<String>> upstreams;

  /**
   * The map an {@code Ack} carries, in a stable order — or null on a qits-ci that knows no public
   * domain.
   */
  public Map<String, String> mirrors() {
    StepAddressPlane.EdgeOrigins origins = addresses.edgeOrigins().orElse(null);
    if (origins == null) {
      return null;
    }
    String registry = authority(origins.artifacts());
    String mirror = authority(origins.mirror());
    StepAddressPlane.ImageRegistries spellings = launcher.imageSpellings();
    Map<String, String> mirrors = new LinkedHashMap<>();
    for (String host : spellings.registrySpellings()) {
      put(mirrors, host, registry);
    }
    for (String host : spellings.mirrorSpellings()) {
      put(mirrors, host, mirror);
    }
    for (String pair : upstreams.orElse(List.of())) {
      int split = pair.indexOf('=');
      if (split <= 0) {
        continue;
      }
      String path = pair.substring(split + 1).trim();
      put(mirrors, pair.substring(0, split), mirror + (path.startsWith("/") ? path : "/" + path));
    }
    return mirrors;
  }

  /** A spelling that IS the public name, or is already mapped, is left as it is. */
  private static void put(Map<String, String> mirrors, String host, String target) {
    if (host == null || host.isBlank()) {
      return;
    }
    String folded = host.trim().toLowerCase(Locale.ROOT);
    if (!folded.equals(target)) {
      mirrors.putIfAbsent(folded, target);
    }
  }

  private static String authority(String origin) {
    String authority = URI.create(origin.trim().replaceAll("/+$", "")).getRawAuthority();
    return authority == null ? origin : authority.toLowerCase(Locale.ROOT);
  }
}
