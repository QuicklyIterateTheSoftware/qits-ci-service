package eu.wohlben.qits.ci.contracts.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiArtifact;
import eu.wohlben.qits.ci.control.CiArtifactPresence.Verdict;
import eu.wohlben.qits.ci.control.CiConfigSource.CommitHeld;
import eu.wohlben.qits.ci.control.CiConfigSource.EventTriggerLookup;
import eu.wohlben.qits.ci.control.CiConfigSource.FileLookup;
import eu.wohlben.qits.ci.control.CiRepoRef;
import eu.wohlben.qits.ci.control.CiStepImagePins;
import eu.wohlben.qits.ci.control.CiTriggerScope;
import eu.wohlben.qits.ci.githost.GitHostClients;
import eu.wohlben.qits.ci.githost.HttpGitConfigSource;
import eu.wohlben.qits.ci.githost.HttpGitHostRepoListing;
import eu.wohlben.qits.ci.githost.IdpGitHostBearer;
import eu.wohlben.qits.ci.idp.IdpClients;
import eu.wohlben.qits.ci.idp.IdpCommissioner;
import eu.wohlben.qits.ci.projects.HttpProjectsRepoListing;
import eu.wohlben.qits.ci.projects.ProjectsClients;
import eu.wohlben.qits.ci.registry.HttpArtifactPresence;
import eu.wohlben.qits.ci.registry.HttpImagePins;
import eu.wohlben.qits.ci.registry.RegistryClients;
import eu.wohlben.qits.pact.consumer.ConsumerPact;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import eu.wohlben.qits.pact.consumer.GoldenMasters;
import eu.wohlben.qits.pact.consumer.GoldenMasters.Operation;
import eu.wohlben.qits.pact.consumer.Trigger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>What qits-ci asks the qits services other than qits-events, and why</b> (qits-1149): one table
 * of rows, each bound to its provider's golden masters through qits-pact-consumer, and each naming
 * the client method that makes the call ({@link Bound#client}, {@link Bound#method}) so {@code
 * ClientCallGuardTest} can hold the table to the code.
 *
 * <p>The providers and their calls:
 *
 * <ul>
 *   <li><b>qits-projects</b>: the repository catalogue, {@code GET /projects/api/repositories}
 *       ({@code projects/HttpProjectsRepoListing}).
 *   <li><b>qits-githost</b>: the storage listing {@code GET /git} ({@code
 *       githost/HttpGitHostRepoListing}, read only while qits-projects is not configured), and the
 *       content reads of {@code githost/HttpGitConfigSource}: a commit's root tree (is the commit
 *       still held?), the {@code .config/qits} tree (which triggers does a repository declare?) and a
 *       file's blob ({@code release.yml}, a trigger file).
 *   <li><b>qits-artifacts</b>: a step image's digest, {@code HEAD /v2/<name>/manifests/<tag>}
 *       ({@code registry/HttpImagePins}); a release artifact's presence and newest version ({@code
 *       registry/HttpArtifactPresence}).
 *   <li><b>qits-idp</b>: commissioned clients and tokens ({@code idp/IdpCommissioner}, Basic auth as
 *       qits-ci's own client), and the client-credentials token mint quarkus-oidc-client makes for
 *       the {@code qits} client ({@code githost/IdpGitHostBearer}).
 * </ul>
 *
 * <p><b>A row whose state its provider has not recorded is pending</b>: it stays in the table, is left
 * out of the pact file, and {@code PeerConsumerPactTest} reports it as skipped with {@code needs
 * provider state '<state>' for <operationId> in <provider>}. The day the provider records it, the pact
 * file test fails until {@code -Dgolden.update=true} rewrites the file, and the row is live. An
 * operationId the provider's route does not carry yet is this consumer's proposal.
 *
 * <p>Requests other than the recorded one are the consumer's own extra headers ({@code X-Qits-User},
 * the git host's bearer, {@code Accept}), which a pact does not bind.
 */
final class PeerContracts {

  /** This repository, as every interaction's {@code qits-trigger} names it. */
  static final String CONSUMER = "qits-ci-service";

  static final GoldenMasters PROJECTS = GoldenMasters.of("qits-projects-service", "qits-projects");
  static final GoldenMasters GITHOST = GoldenMasters.of("qits-githost-service", "qits-githost");
  static final GoldenMasters ARTIFACTS = GoldenMasters.of("qits-artifacts-service", "qits-artifacts");
  static final GoldenMasters IDP = GoldenMasters.of("qits-idp-service", "qits-idp");

  /** The client qits-ci commissions as in the idp's recordings: {@code test-broad}. */
  static final String IDP_CLIENT_ID = "test-broad";

  static final String IDP_CLIENT_SECRET = "test-broad-secret";

  /** The registry host a step image names; the digest is asked of the artifacts origin. */
  static final String REGISTRY_HOST = "registry.qits.contract.invalid";

  /** The real client, pointed at the mock server's url, making the call and asserting the answer. */
  @FunctionalInterface
  interface Call {
    void run(String url, Operation recorded) throws Exception;
  }

  /**
   * One row, the client method that makes its call, and that call.
   *
   * @param client the class whose method makes the request
   * @param method that method's name
   */
  record Bound(
      GoldenMasters provider, GoldenInteraction row, Class<?> client, String method, Call call) {}

  private static final Trigger CANDIDATES =
      Trigger.event("* (CiEventTriggerListener: the candidate repositories for an event)");

  static final List<Bound> ROWS = rows();

  /** Every provider's pact, of its rows. */
  static final List<ConsumerPact> PACTS =
      List.of(pact(PROJECTS), pact(GITHOST), pact(ARTIFACTS), pact(IDP));

  private PeerContracts() {}

  static ConsumerPact pact(GoldenMasters provider) {
    return ConsumerPact.of(
        CONSUMER,
        provider,
        ROWS.stream().filter(b -> b.provider() == provider).map(Bound::row).toList());
  }

  static ConsumerPact pactOf(Bound bound) {
    return PACTS.stream().filter(p -> p.masters() == bound.provider()).findFirst().orElseThrow();
  }

  private static List<Bound> rows() {
    List<Bound> rows = new ArrayList<>();

    // --- qits-projects ------------------------------------------------------------------------
    for (Trigger trigger :
        List.of(CANDIDATES, Trigger.schedule("CiRunnerHealth.healthcheck"))) {
      rows.add(
          new Bound(
              PROJECTS,
              GoldenInteraction.of(trigger, "a project with 3 repositories", "listRepositories")
                  .consumes("repositories[].id", "repositories[].projectId", "repositories[].name"),
              HttpProjectsRepoListing.class,
              "repositories",
              (url, recorded) -> {
                List<CiRepoRef> refs = ProjectsClients.repoListing(url).repositories();
                assertFalse(refs.isEmpty(), "the catalogue's repositories are candidates");
                refs.forEach(ref -> assertTrue(ref.named(), ref + " carries its public name"));
              }));
    }

    // --- qits-githost -------------------------------------------------------------------------
    rows.add(
        new Bound(
            GITHOST,
            GoldenInteraction.of(CANDIDATES, "two repositories", "listGitRepositories")
                .consumes("repositories[]"),
            HttpGitHostRepoListing.class,
            "repositories",
            (url, recorded) -> {
              // The pact answers the recorded list's first entry as the template of every entry.
              Set<String> ids = GitHostClients.repoListing(url).repositories();
              assertFalse(ids.isEmpty(), "the listing's ids are candidates");
              assertTrue(ids.contains(recorded.params().get("firstRepositoryId")), ids.toString());
            }));
    Trigger stillHeld = Trigger.schedule("CiRunService.runSteps (is the run's commit still on the host?)");
    rows.add(
        new Bound(
            GITHOST,
            GoldenInteraction.of(stillHeld, "a repository with files on main", "getRootTree"),
            HttpGitConfigSource.class,
            "commitHeld",
            (url, recorded) ->
                assertEquals(
                    CommitHeld.HELD,
                    gitConfig(url).commitHeld(repo(recorded), recorded.params().get("sha")))));
    rows.add(
        new Bound(
            GITHOST,
            GoldenInteraction.of(stillHeld, "a repository missing the requested commit", "getRootTree"),
            HttpGitConfigSource.class,
            "commitHeld",
            (url, recorded) ->
                assertEquals(
                    CommitHeld.GONE,
                    gitConfig(url).commitHeld(repo(recorded), recorded.params().get("sha")))));
    rows.add(
        new Bound(
            GITHOST,
            GoldenInteraction.of(
                    Trigger.event("* (CiTriggerListingCache: which triggers does the repository declare?)"),
                    "a repository with files on main",
                    "getTree")
                .consumes("entries[].name")
                .readsHeader("Git-Commit-Sha"),
            HttpGitConfigSource.class,
            "readEventTriggers",
            (url, recorded) -> {
              // The recorded directory holds no ci-event-*.yml, so the lookup ends at the listing.
              EventTriggerLookup lookup =
                  gitConfig(url)
                      .readEventTriggers(
                          repo(recorded), recorded.params().get("rev"), CiTriggerScope.REPOSITORY);
              assertEquals(EventTriggerLookup.Status.FOUND, lookup.status());
              assertEquals(recorded.responseHeader("Git-Commit-Sha"), lookup.headSha());
              assertTrue(lookup.files().isEmpty(), "no file of the listing is a trigger file");
            }));
    Trigger releaseSlot = Trigger.event("* (CiEventTriggerService: the release slot, .config/qits/release.yml)");
    rows.add(
        new Bound(
            GITHOST,
            // Status only: the recording holds no body for a blob (its answer is raw bytes).
            GoldenInteraction.of(releaseSlot, "a repository with files on main", "getBlob"),
            HttpGitConfigSource.class,
            "readFile",
            (url, recorded) ->
                assertEquals(
                    FileLookup.Status.FOUND,
                    gitConfig(url)
                        .readFile(
                            repo(recorded),
                            recorded.params().get("rev"),
                            recorded.params().get("path"))
                        .status())));
    rows.add(
        new Bound(
            GITHOST,
            GoldenInteraction.of(releaseSlot, "a repository missing the requested path", "getBlob"),
            HttpGitConfigSource.class,
            "readFile",
            (url, recorded) ->
                assertEquals(
                    FileLookup.Status.ABSENT,
                    gitConfig(url)
                        .readFile(
                            repo(recorded),
                            recorded.params().get("rev"),
                            recorded.params().get("path"))
                        .status())));
    // PENDING: a trigger file's own read, at the listing's head sha. No recording names a
    // .config/qits/ci-event-*.yml file.
    rows.add(
        new Bound(
            GITHOST,
            GoldenInteraction.of(
                Trigger.event("* (CiTriggerListingCache: a declared ci-event trigger file)"),
                "a repository declaring an event trigger",
                "getBlob"),
            HttpGitConfigSource.class,
            "readEventTriggers",
            (url, recorded) ->
                assertEquals(
                    FileLookup.Status.FOUND,
                    gitConfig(url)
                        .readFile(
                            repo(recorded),
                            recorded.params().get("rev"),
                            recorded.params().get("path"))
                        .status())));

    // --- qits-artifacts -----------------------------------------------------------------------
    Trigger releaseJoin = Trigger.schedule("ReleaseJoin (is a release's artifact in the store?)");
    rows.add(
        new Bound(
            ARTIFACTS,
            GoldenInteraction.of(
                    releaseJoin, "a content hash recorded for the coordinate", "getNewestContentHash")
                .consumes("version"),
            HttpArtifactPresence.class,
            "newest",
            (url, recorded) -> {
              var probe =
                  RegistryClients.presence(url)
                      .newest(
                          CiArtifact.Type.valueOf(recorded.params().get("type").toUpperCase()),
                          recorded.params().get("name"));
              assertEquals(Verdict.PRESENT, probe.verdict(), probe.detail());
              assertEquals(recorded.params().get("version"), probe.version());
            }));
    // PENDING: qits-artifacts records none of its maven, npm or registry routes.
    rows.add(
        new Bound(
            ARTIFACTS,
            GoldenInteraction.of(releaseJoin, "a maven artifact published at a version", "getMavenPom"),
            HttpArtifactPresence.class,
            "probe",
            (url, recorded) ->
                assertEquals(
                    Verdict.PRESENT,
                    RegistryClients.presence(url)
                        .probe(
                            CiArtifact.Type.MAVEN,
                            recorded.params().get("name"),
                            recorded.params().get("version"))
                        .verdict())));
    rows.add(
        new Bound(
            ARTIFACTS,
            GoldenInteraction.of(releaseJoin, "an npm package published at a version", "getNpmPackument")
                .consumes("versions"),
            HttpArtifactPresence.class,
            "probe",
            (url, recorded) ->
                assertEquals(
                    Verdict.PRESENT,
                    RegistryClients.presence(url)
                        .probe(
                            CiArtifact.Type.NPM,
                            recorded.params().get("name"),
                            recorded.params().get("version"))
                        .verdict())));
    rows.add(
        new Bound(
            ARTIFACTS,
            GoldenInteraction.of(
                    Trigger.schedule("CiRunService.pinStepImages (a step image's digest)"),
                    "an image pushed under a tag",
                    "headImageManifest")
                .readsHeader("Docker-Content-Digest"),
            HttpImagePins.class,
            "pin",
            (url, recorded) -> {
              CiStepImagePins.Pin pin =
                  RegistryClients.imagePins(REGISTRY_HOST, url)
                      .pin(
                          REGISTRY_HOST
                              + "/"
                              + recorded.params().get("name")
                              + ":"
                              + recorded.params().get("tag"));
              assertEquals(CiStepImagePins.Status.PINNED, pin.status(), pin.detail());
            }));

    // --- qits-idp -----------------------------------------------------------------------------
    rows.add(
        new Bound(
            IDP,
            GoldenInteraction.of(
                    Trigger.operation("CiRunnerController.register"),
                    "a service client with the system role",
                    "commissionClient")
                .header("Authorization", "{authorization}")
                .consumes("clientId", "secret"),
            IdpCommissioner.class,
            "commission",
            (url, recorded) -> {
              IdpCommissioner.Commission commission =
                  commissioner(url).commission("contract", "run-1", null);
              assertFalse(commission.clientId().isBlank());
              assertFalse(commission.secret().isBlank());
            }));
    rows.add(
        new Bound(
            IDP,
            GoldenInteraction.of(
                    Trigger.schedule("RunCommissions.forRun (a step's run token)"),
                    "a service client with the system role",
                    "commissionToken")
                .header("Authorization", "{authorization}")
                .consumes("tokenId", "token", "subject"),
            IdpCommissioner.class,
            "commissionToken",
            (url, recorded) -> {
              IdpCommissioner.CommissionedToken token =
                  commissioner(url).commissionToken("contract", "run-1", null);
              assertFalse(token.tokenId().isBlank());
              assertFalse(token.token().isBlank());
              assertFalse(token.subject().isBlank());
            }));
    Trigger reconcile = Trigger.schedule("CommissionReconciler.tick");
    rows.add(
        new Bound(
            IDP,
            GoldenInteraction.of(reconcile, "a commissioned client", "listClients")
                .header("Authorization", "{authorization}")
                .consumes("[].clientId", "[].contextKind", "[].contextId"),
            IdpCommissioner.class,
            "live",
            (url, recorded) -> {
              List<IdpCommissioner.LiveClient> live = commissioner(url).live().orElseThrow();
              assertEquals(recorded.params().get("clientId"), live.getFirst().clientId());
            }));
    rows.add(
        new Bound(
            IDP,
            GoldenInteraction.of(reconcile, "a commissioned client", "decommissionClient")
                .header("Authorization", "{authorization}"),
            IdpCommissioner.class,
            "decommission",
            (url, recorded) -> commissioner(url).decommission(recorded.params().get("clientId"))));
    rows.add(
        new Bound(
            IDP,
            GoldenInteraction.of(reconcile, "a commissioned token", "listTokens")
                .header("Authorization", "{authorization}")
                .consumes(
                    "[].tokenId", "[].subject", "[].contextKind", "[].contextId", "[].createdAt"),
            IdpCommissioner.class,
            "liveTokens",
            (url, recorded) -> {
              List<IdpCommissioner.LiveToken> live = commissioner(url).liveTokens().orElseThrow();
              assertEquals(recorded.params().get("tokenId"), live.getFirst().tokenId());
              assertTrue(live.getFirst().createdAt() != null, "createdAt reads as an instant");
            }));
    rows.add(
        new Bound(
            IDP,
            GoldenInteraction.of(
                    Trigger.schedule("RunCommissions.release (a finished run's token)"),
                    "a commissioned token",
                    "deleteToken")
                .header("Authorization", "{authorization}"),
            IdpCommissioner.class,
            "deleteToken",
            (url, recorded) ->
                assertTrue(commissioner(url).deleteToken(recorded.params().get("tokenId")))));
    // PENDING: the client-credentials mint of the `qits` client, as quarkus-oidc-client sends it —
    // Basic auth and `grant_type=client_credentials&audience=qits-platform`. The idp's recorded
    // client-credentials grant carries no audience. Run by IdpTokenMintPactTest, which needs the
    // Quarkus runtime the OIDC client lives in.
    rows.add(
        new Bound(
            IDP,
            GoldenInteraction.of(
                    Trigger.schedule("IdpGitHostBearer.token (every git host read)"),
                    "a service client minting a platform-audience token",
                    "issueToken")
                .header("Authorization", "{authorization}")
                .consumes("access_token"),
            IdpGitHostBearer.class,
            "token",
            null));
    return List.copyOf(rows);
  }

  private static HttpGitConfigSource gitConfig(String url) {
    return GitHostClients.configSource(url, "machine-token");
  }

  private static IdpCommissioner commissioner(String url) {
    return IdpClients.commissioner(url + "/idp", IDP_CLIENT_ID, IDP_CLIENT_SECRET);
  }

  /** The recorded repository, addressed by its public coordinate as the content routes are. */
  private static CiRepoRef repo(Operation recorded) {
    Map<String, String> params = recorded.params();
    return CiRepoRef.of(
        params.get("repositoryId"), params.get("projectId"), params.get("repoName"));
  }
}
