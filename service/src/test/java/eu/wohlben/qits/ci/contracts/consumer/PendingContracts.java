package eu.wohlben.qits.ci.contracts.consumer;

import java.util.List;

/**
 * <b>The REST calls qits-ci makes that no pact can bind yet, outside {@link PeerContracts}</b>
 * (qits-1149): the eventstream library's outbox publish, which qits-events records no state for.
 * The calls to qits-projects, qits-githost, qits-artifacts and qits-idp moved to {@link
 * PeerContracts}, whose own pending rows are reported by {@code PeerConsumerPactTest}. Each row here
 * lacks the golden master (and the provider state) the interaction must be built from. Each row is the
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
              "PUT of a new event id answering 201 (or 200); qits-ci reads the status only"));

  private PendingContracts() {}
}
