package eu.wohlben.qits.ci.stories.support;

import eu.wohlben.qits.servicemock.idp.MockIdp;
import io.restassured.specification.RequestSpecification;

/**
 * The two identity tracks qits-ci accepts, one helper each — because a story that presented the
 * wrong one would be documenting a door that does not exist.
 *
 * <h2>A machine is a bearer</h2>
 *
 * <p>{@link #platformService(RequestSpecification)} presents an RS256 token minted by {@link
 * MockIdp} against the very JWKS the launched process fetched at startup: {@code aud=qits-platform}
 * (what {@code qits.auth.machine.platform-audience} admits), {@code groups=[qits:system]} — the
 * machine role quarkus-oidc reads as a role with no configuration — and {@code project=*}, which is
 * what {@code POST /ci/api/events/trigger} demands, since an event names no repository and the
 * repository-scoped grant it would otherwise need is "all of them".
 *
 * <h2>A person is a bearer too</h2>
 *
 * <p>{@link #operator(RequestSpecification)} presents a person's token from the same idp: the
 * operator's name in {@code sub}, the platform audience and {@code qits:admin} in {@code groups},
 * and no {@code project} claim. Forward-auth — the {@code X-Qits-User} / {@code X-Qits-Roles} pair
 * the edge used to assert — leaves qits-auth-core with qits-1338 (epic qits-543, qits-1359), so a
 * signed-in person reaches this service the way a machine does, and the roles are what tell them
 * apart: a person's {@code qits:admin} with no {@code project} claim is admitted to every project.
 *
 * <p><b>There is no synthetic {@code dev} identity here.</b> A launched artifact runs under neither
 * {@code %dev} nor {@code %test}, so an anonymous request really is anonymous and the roles below
 * are the only thing opening these doors.
 */
public final class StoryIdentities {

  /** The audience this service enforces — a literal, because {@code application.properties} pins it. */
  public static final String AUDIENCE = "qits-platform";

  /** The machine role a platform peer holds, and what {@code CiEventController} requires. */
  public static final String MACHINE_ROLE = "qits:system";

  /** The human role the run listing accepts beside the machine one. */
  public static final String HUMAN_ROLE = "qits:admin";

  private StoryIdentities() {}

  /**
   * A platform peer's bearer, wide enough to trigger an evaluation over every repository.
   *
   * <p>Minted fresh per call rather than cached: a token is a credential and a helper that handed
   * the same string to two stories would make {@link
   * eu.wohlben.qits.userflows.report.ReportAssertions#assertNotLeaked} a weaker claim than it reads
   * as.
   */
  public static String platformToken() {
    return MockIdp.attach()
        .token()
        .subject("qits-platform-orchestrator")
        .audience(AUDIENCE)
        .groups(MACHINE_ROLE)
        .claim("project", "*")
        .mint();
  }

  /** {@code given()} with a platform peer's bearer on it. */
  public static RequestSpecification platformService(RequestSpecification request) {
    return request.header("Authorization", "Bearer " + platformToken());
  }

  /** {@code given()} carrying a signed-in operator's bearer. */
  public static RequestSpecification operator(RequestSpecification request) {
    return request.header("Authorization", "Bearer " + operatorToken());
  }

  /** A freshly minted person's bearer: {@code alice}, the platform audience, {@code qits:admin}. */
  public static String operatorToken() {
    return MockIdp.attach()
        .token()
        .subject("alice")
        .audience(AUDIENCE)
        .groups(HUMAN_ROLE)
        .mint();
  }
}
