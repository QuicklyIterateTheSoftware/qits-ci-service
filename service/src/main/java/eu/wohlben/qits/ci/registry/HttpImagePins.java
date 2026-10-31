package eu.wohlben.qits.ci.registry;

import eu.wohlben.qits.ci.control.CiStepImagePins;
import eu.wohlben.qits.ci.targethost.CiSlotAddresses;
import eu.wohlben.qits.http.QitsHttpClients;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The sole production {@link CiStepImagePins}: one {@code HEAD
 * <artifacts>/v2/<name>/manifests/<tag>} against the platform's own registry, and the {@code
 * Docker-Content-Digest} it answers with.
 *
 * <h2>Why this is the mechanism, and why there is no other one</h2>
 *
 * <p><b>The digest is the registry's to state, and the registry states it in a header.</b> The OCI
 * distribution spec makes {@code Docker-Content-Digest} the answer to "which bytes does this tag
 * name right now", and qits-platform-artifacts serves the spec at the literal {@code /v2} of its
 * own root ({@code RegistryRoutes}, conformance-tested in that repository). A {@code HEAD} costs
 * the manifest's headers and none of its body, which is all this question needs.
 *
 * <p><b>The address is DERIVED, never configured</b> — {@code IdpCommissioner}'s rule, for its
 * reason: a second key is a second thing to keep in step with the first, and a pin resolved against
 * one store and pulled from another is worse than no pin. It is the origin of {@code
 * qits.artifacts.maven.registry-url}, which is set on every live deployment. That origin and the {@code /v2} registry are one
 * service by construction: the registry cannot be mounted anywhere else — docker resolves a
 * reference against {@code <host>/v2/} and accepts no path prefix.
 *
 * <p><b>Two names, one registry, and each is used for what it is — and neither is configured.</b>
 * What this process dials is the in-network origin above. What a pinned reference is ADDRESSED
 * under is the registry's public name, {@code registry.qits.<QITS_DOMAIN>}, derived by {@link
 * CiSlotAddresses} exactly as every other address a lease is told — the name a target really pulls
 * from. Resolving through one and pulling through another is correct and not a mismatch: a digest
 * is content-addressed, so the same digest names the same bytes at whichever address they are read.
 *
 * <p><b>There is no registry host key any more (qits-757).</b> One used to name the host a pinned
 * reference was built with and recognised by; its shipped default was a host-loopback machine name
 * that ended up on every step tile. A reference is now this platform's when it is BARE with {@code
 * qits.artifacts.image-repository} as its first path segment — {@code
 * qits/build-images/ci-slot:latest}, the spelling every recipe and every step row uses — or when it
 * names the derived public registry host.
 *
 * <p><b>No credential, deliberately.</b> qits-artifacts' {@code PublishGuard} guards the six
 * publish surfaces and lets every read through untouched, so a manifest read needs none — and
 * presenting one would be a credential offered where the store asks for nothing, on the trigger
 * worker, once per distinct image per accepted run.
 *
 * <h2>What is NOT pinned, and why that is an answer rather than a gap</h2>
 *
 * <p>A reference this platform does not publish is {@link CiStepImagePins.Status#FOREIGN}: {@code
 * alpine:3} and {@code docker:28-dind} come from Docker Hub, and anything naming another registry
 * comes from wherever it says. qits-ci holds no credential for those stores, no address to them
 * that is a deployment fact anybody has stated, and the token dance Docker Hub demands is a second
 * availability added to every accept. So they are launched as named and the run row records no pin
 * for them, which says "this reference floated" out loud rather than leaving it to be assumed. The
 * owner's complaint is about {@code qits/build-images/*:latest} — the platform's own images, in the
 * platform's own registry — and that is exactly the set this covers.
 *
 * <p>A reference that already carries {@code @sha256:…} is {@link
 * CiStepImagePins.Status#ALREADY_PINNED} and no registry is asked at all. Re-resolving a digest
 * would be turning a deliberate pin into whatever {@code latest} is now, which is the defect this
 * class exists to close wearing the fix's clothes.
 *
 * <h2>The timeouts, and the thread they are for</h2>
 *
 * <p>This sits on {@code ci-trigger-worker} in front of accepting a run, the thread {@code
 * HttpGitHostRepoListing} argues its own 2s/3s for, and it is paid once per distinct image per run
 * rather than per candidate. Same bounds for the same reason: an untimed call would hold every
 * arriving event, and past the deadline the honest answer is that nothing was learned.
 *
 * <p><b>Nothing is cached across runs.</b> A cache would be a pin one run chose being spent by
 * another, which is the straddle at a coarser grain — two builds a minute apart reporting they used
 * the same toolchain when one of them did not. The memo that matters is per run and lives in {@code
 * CiRunService.pinStepImages}.
 *
 * <p>An instance {@code HttpClient} rather than a static one, {@code HttpGitHostRepoListing}'s
 * constraint: a static client is created at image-build time and native-image refuses the heap it
 * lands in. Wrapped through {@link QitsHttpClients}, like every hand-rolled client in this
 * deployable. Nothing here binds a record, so no reflection is owed either.
 */
@ApplicationScoped
public class HttpImagePins implements CiStepImagePins {

  private static final Logger LOG = Logger.getLogger(HttpImagePins.class);

  /** Bound on opening the socket — see the class javadoc. */
  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

  /** Bound on the whole exchange. */
  static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);

  /**
   * What the registry answers with. Spelled here because it is a wire contract of the distribution
   * spec rather than a header this platform chose.
   */
  static final String DIGEST_HEADER = "Docker-Content-Digest";

  /**
   * Every manifest media type a step image can be, so the registry does not answer 404 on an index
   * it would happily serve. Both index spellings first: a multi-arch image's index digest is the
   * one a pull resolves, and asking only for a manifest would pin one architecture's child.
   */
  static final String MANIFEST_ACCEPT =
      String.join(
          ", ",
          "application/vnd.oci.image.index.v1+json",
          "application/vnd.docker.distribution.manifest.list.v2+json",
          "application/vnd.oci.image.manifest.v1+json",
          "application/vnd.docker.distribution.manifest.v2+json");

  private HttpClient client;

  /**
   * The platform's image namespace: a bare reference whose first path segment is this is ours —
   * see {@link Ref#parse}.
   */
  @ConfigProperty(name = "qits.artifacts.image-repository")
  String artifactsImageRepository;

  /** Where the registry's public name comes from — {@code registry.qits.<QITS_DOMAIN>}. */
  @Inject CiSlotAddresses addresses;

  /** {@code qits.artifacts.url} when a deployment set it — see {@link #registryApiOrigin}. */
  @ConfigProperty(name = "qits.artifacts.url")
  Optional<String> artifactsUrl;

  @ConfigProperty(name = "qits.artifacts.maven.registry-url")
  String artifactsMavenRegistryUrl;

  @Inject QitsHttpClients clients;

  /**
   * Built here rather than at the field — see {@code HttpGitConfigSource#buildClient} for why a
   * field initializer cannot reach an injected collaborator.
   */
  @PostConstruct
  void buildClient() {
    client = clients.wrap(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
  }

  @Override
  public Pin pin(String reference) {
    if (reference == null || reference.isBlank()) {
      return Pin.foreign(reference);
    }
    if (reference.indexOf('@') >= 0) {
      return Pin.alreadyPinned(reference);
    }
    String registry = publicRegistryHost();
    Ref ref = Ref.parse(reference, artifactsImageRepository, registry);
    if (ref == null) {
      return Pin.foreign(reference);
    }
    String origin = registryApiOrigin();
    if (origin.isBlank()) {
      // OURS AND UNASKABLE. The deployment carries no address for its own artifacts store, so the
      // question cannot be put — which is not "not ours to pin" and must not answer as if it were.
      return Pin.unresolved(
          reference,
          "this deployment states no qits-artifacts origin (qits.artifacts.url,"
              + " qits.artifacts.maven.registry-url), so the registry cannot be asked for a digest");
    }
    String url = origin + "/v2/" + ref.name() + "/manifests/" + ref.tag();
    try {
      HttpResponse<Void> response =
          client.send(
              HttpRequest.newBuilder(URI.create(url))
                  .timeout(REQUEST_TIMEOUT)
                  .header("Accept", MANIFEST_ACCEPT)
                  .method("HEAD", HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.discarding());
      if (response.statusCode() != 200) {
        return Pin.unresolved(
            reference, "the registry answered HTTP " + response.statusCode() + " for " + url);
      }
      String digest = response.headers().firstValue(DIGEST_HEADER).orElse(null);
      if (digest == null || !digest.startsWith("sha256:")) {
        // A 200 with no usable digest is still nothing learned. Never fall through to the tag: that
        // is precisely the "whatever :latest is now" this class exists to stop.
        return Pin.unresolved(
            reference, "the registry answered 200 for " + url + " with no usable " + DIGEST_HEADER);
      }
      LOG.debugf("Pinned %s to %s", reference, digest);
      return Pin.pinned(ref.pinned(registry, digest));
    } catch (InterruptedException interrupted) {
      // Restore and answer "nothing learned", the arrangement every blocking helper on this call
      // path runs (IdpCommissioner, HttpGitConfigSource): locally correct here, and the claim
      // loop's own leak check is what keeps a restored flag from retiring a worker.
      Thread.currentThread().interrupt();
      return Pin.unresolved(reference, "the registry at " + url + " was not asked: interrupted");
    } catch (Exception e) {
      return Pin.unresolved(reference, "the registry at " + url + " could not be asked: " + e);
    }
  }

  /**
   * The origin this process dials the registry at — {@code qits.artifacts.url} when a deployment
   * states one, otherwise the scheme and authority of {@code qits.artifacts.maven.registry-url}.
   *
   * <p>This is where qits-ci ITSELF asks about the store, from inside the swarm — not an address a
   * step is told. A step reads the same store at its public name ({@code CiSlotAddresses}); a
   * digest is content-addressed, so the pin resolved here names the same bytes there.
   */
  String registryApiOrigin() {
    // One derivation for every qits-ci -> qits-artifacts read (ArtifactsOrigin), so the digest
    // lookup and the release join's presence check can never dial two different stores.
    return ArtifactsOrigin.of(artifactsUrl, artifactsMavenRegistryUrl);
  }

  /**
   * The registry's public host, {@code registry.qits.<domain>}, or null when this qits-ci knows no
   * public domain — derived, never configured, by the one derivation every lease address uses.
   */
  String publicRegistryHost() {
    return addresses == null
        ? null
        : addresses.plane().map(CiSlotAddresses.Plane::registryHost).orElse(null);
  }

  /**
   * One reference split the way the registry addresses it, or null when it is not this platform's
   * to pin.
   *
   * @param name the repository/image path — {@code qits/build-images/ci-base}
   * @param tag the tag, {@code latest} when the reference named none, which is what docker resolves
   */
  record Ref(String name, String tag) {

    /**
     * <b>Ours is this platform's namespace, bare, or the platform's public registry host.</b> A
     * bare reference whose first path segment is {@code qits.artifacts.image-repository} — {@code
     * qits/build-images/ci-slot:latest} — is one this process can ask about, and so is one naming
     * {@code registry.qits.<domain>}. Everything else — a bare official image, another namespace,
     * another registry, a mirror — is somebody else's store and is {@code FOREIGN}. The narrowness
     * is the safety property: the only thing claimed here is what the platform published itself.
     *
     * @param imageRepository {@code qits.artifacts.image-repository}
     * @param registryHost the public registry host, or null when no domain is known
     */
    static Ref parse(String reference, String imageRepository, String registryHost) {
      int slash = reference.indexOf('/');
      if (slash <= 0) {
        return null;
      }
      String first = reference.substring(0, slash);
      String rest;
      if (looksLikeHost(first)) {
        // A reference that names its registry: ours only when it is the platform's public name.
        if (!isOurs(first, registryHost)) {
          return null;
        }
        rest = reference.substring(slash + 1);
      } else {
        // Bare: docker would resolve it against Docker Hub, and it is ours only in our namespace.
        if (imageRepository == null
            || imageRepository.isBlank()
            || !first.equals(imageRepository.trim())) {
          return null;
        }
        rest = reference;
      }
      int colon = rest.lastIndexOf(':');
      // A colon before a slash is a port in a path segment, which no image name has — but the check
      // is cheap and the alternative is a name silently truncated into an unaskable one.
      String name = colon > 0 && rest.indexOf('/', colon) < 0 ? rest.substring(0, colon) : rest;
      String tag = colon > 0 && rest.indexOf('/', colon) < 0 ? rest.substring(colon + 1) : "latest";
      if (name.isBlank() || tag.isBlank()) {
        return null;
      }
      return new Ref(name, tag);
    }

    /**
     * The pinned reference: under the registry's public name, or — when this qits-ci knows no
     * domain — bare, as spelled, which the lease's address plane would put under that name anyway.
     */
    String pinned(String registryHost, String digest) {
      String path = name + "@" + digest;
      return registryHost == null || registryHost.isBlank() ? path : registryHost + "/" + path;
    }

    /** A first segment carrying a {@code .} or a {@code :}, or {@code localhost}, is a registry. */
    private static boolean looksLikeHost(String first) {
      return first.indexOf('.') >= 0 || first.indexOf(':') >= 0 || "localhost".equals(first);
    }

    private static boolean isOurs(String host, String registryHost) {
      // Case-insensitively, because a registry authority is a hostname and DNS does not care —
      // while an image NAME does, which is why only this half is folded.
      return registryHost != null
          && !registryHost.isBlank()
          && host.toLowerCase(Locale.ROOT).equals(registryHost.trim().toLowerCase(Locale.ROOT));
    }
  }
}
