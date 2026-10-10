package eu.wohlben.qits.ci.contracts.consumer;

import java.util.List;

/**
 * <b>The REST calls qits-ci makes that no pact can bind yet</b> (qits-1149): each provider lacks
 * the golden master (and the provider state) the interaction must be built from. Each row is the
 * interaction as it will be written — provider, operation, request, the body paths qits-ci reads
 * (empty: status only), the trigger, and the provider state it needs. {@code
 * PendingContractsTest} reports every row as a skipped test naming that state, so the gap shows in
 * every run. When a provider publishes the state, move the row into a real contract table (like
 * {@link EventsContract}) and add the provider's pact to {@code release.yml}'s {@code contracts:}.
 *
 * <p>A provider {@code operationId} in angle brackets does not exist yet: the provider's route
 * carries none, so the name is this consumer's proposal.
 */
final class PendingContracts {

  record Pending(
      String provider,
      String operationId,
      String method,
      String path,
      String request,
      List<String> consumes,
      Trigger trigger,
      String state,
      String recording) {

    String reason() {
      return "needs provider state '" + state + "' for " + operationId + " in " + provider;
    }
  }

  private static final String EVENTS = "qits-events-service";
  private static final String PROJECTS = "qits-projects-service";
  private static final String GITHOST = "qits-githost-service";
  private static final String IDP = "qits-idp-service";

  static final List<Pending> ROWS =
      List.of(
          // --- qits-events, through the qits-eventstream library's outbox ----------------------
          new Pending(
              EVENTS,
              "<publishEvent>",
              "PUT",
              "/events/api/events/{id}",
              "the event envelope: name, occurredAt, payload, description, parentId, environment",
              List.of(),
              Trigger.schedule("OutboxSweeper (BuildAnnouncer, SoftwareReleaseAnnouncer,"
                  + " RunnerLifecycleAnnouncer)"),
              "no event with the given id",
              "PUT of a new event id answering 201 (or 200); qits-ci reads the status only"),
          // --- qits-projects --------------------------------------------------------------------
          new Pending(
              PROJECTS,
              "<listRepositoryCoordinates>",
              "GET",
              "/projects/api/repositories",
              "no body",
              List.of("$.repositories[*].id", "$.repositories[*].projectId", "$.repositories[*].name"),
              Trigger.event("* (CiEventTriggerListener: candidate repositories for any event)"),
              "repositories with public coordinates",
              "GET answering 200 with at least one repository carrying id, projectId and name"),
          new Pending(
              PROJECTS,
              "<listRepositoryCoordinates>",
              "GET",
              "/projects/api/repositories",
              "no body",
              List.of("$.repositories[*].id", "$.repositories[*].projectId", "$.repositories[*].name"),
              Trigger.schedule("CiRunnerHealth.healthcheck"),
              "repositories with public coordinates",
              "as above"),
          // --- qits-githost (content routes; none carries an operationId) ------------------------
          new Pending(
              GITHOST,
              "<listGitRepositories>",
              "GET",
              "/git",
              "no body; only when qits.ci.projects-url is not set",
              List.of("$.repositories[*]"),
              Trigger.event("* (CiEventTriggerListener: candidate repositories for any event)"),
              "two repositories",
              "GET answering 200 with {\"repositories\": [<repository id>, ...]}"),
          new Pending(
              GITHOST,
              "<getTree>",
              "GET",
              "/git/{projectId}/{repoName}/tree/{rev}",
              "no body",
              List.of(),
              Trigger.schedule("CiRunService.runSteps (is the run's commit still on the host?)"),
              "a repository with a commit",
              "GET answering 200 for a commit the repository holds, and 404 for one it does not"),
          new Pending(
              GITHOST,
              "<getTree>",
              "GET",
              "/git/{projectId}/{repoName}/tree/{rev}/.config/qits",
              "no body",
              List.of("$.entries[*].name", "header Git-Commit-Sha"),
              Trigger.event("* (CiEventTriggerListener: which triggers does the repository declare?)"),
              "a repository declaring event triggers",
              "GET answering 200 with header Git-Commit-Sha and entries[*].name; 404 when the"
                  + " directory is absent (then the tree root answers 200 with Git-Commit-Sha)"),
          new Pending(
              GITHOST,
              "<getBlob>",
              "GET",
              "/git/{projectId}/{repoName}/blob/{rev}/{path}",
              "no body; the answer is the file's raw bytes, not JSON",
              List.of("raw body"),
              Trigger.event("* (CiEventTriggerService: release slot, .config/qits/release.yml)"),
              "a repository declaring a release slot",
              "GET answering 200 with the file's bytes, and 404 for a path the commit lacks"),
          // --- qits-idp (IdpCommissioner; no route carries an operationId) ---------------------
          new Pending(
              IDP,
              "<commissionClient>",
              "POST",
              "/idp/api/clients",
              "{contextKind, contextId, gitRefs[]}; Basic auth as qits-ci's own client",
              List.of("$.clientId", "$.secret"),
              Trigger.operation("CiRunnerController.register"),
              "a service allowed to commission clients",
              "POST answering 201 with clientId and secret"),
          new Pending(
              IDP,
              "<listClients>",
              "GET",
              "/idp/api/clients",
              "no body; Basic auth",
              List.of("$[*].clientId", "$[*].contextKind", "$[*].contextId"),
              Trigger.schedule("CommissionReconciler.tick"),
              "a service with a commissioned client",
              "GET answering 200 with a JSON array of clients (top-level array)"),
          new Pending(
              IDP,
              "<decommissionClient>",
              "DELETE",
              "/idp/api/clients/{clientId}",
              "no body; Basic auth",
              List.of(),
              Trigger.schedule("CommissionReconciler.tick"),
              "a service with a commissioned client",
              "DELETE answering 204 (404 is also accepted)"),
          new Pending(
              IDP,
              "<commissionToken>",
              "POST",
              "/idp/api/tokens",
              "{contextKind, contextId, gitRefs[]}; Basic auth",
              List.of("$.tokenId", "$.token", "$.subject"),
              Trigger.schedule("RunnerStepRunner (RunCommissions.forRun: a step's run token)"),
              "a service allowed to commission tokens",
              "POST answering 201 with tokenId, token and subject"),
          new Pending(
              IDP,
              "<listTokens>",
              "GET",
              "/idp/api/tokens",
              "no body; Basic auth",
              List.of(
                  "$[*].tokenId",
                  "$[*].subject",
                  "$[*].contextKind",
                  "$[*].contextId",
                  "$[*].createdAt"),
              Trigger.schedule("CommissionReconciler.tick"),
              "a service with a commissioned token",
              "GET answering 200 with a JSON array of tokens (top-level array)"),
          new Pending(
              IDP,
              "<deleteToken>",
              "DELETE",
              "/idp/api/tokens/{tokenId}",
              "no body; Basic auth",
              List.of(),
              Trigger.schedule("RunCommissions.release (a finished run)"),
              "a service with a commissioned token",
              "DELETE answering 204 (404 is also accepted)"));

  private PendingContracts() {}
}
