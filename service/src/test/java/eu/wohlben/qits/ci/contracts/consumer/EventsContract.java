package eu.wohlben.qits.ci.contracts.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import au.com.dius.pact.consumer.dsl.PactBuilder;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Pact;
import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.ci.bus.BuildSuccessfulListener;
import eu.wohlben.qits.ci.bus.CiEventTriggerListener;
import eu.wohlben.qits.ci.bus.RepositoryRenamedListener;
import eu.wohlben.qits.ci.bus.ScmReleaseListener;
import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.QitsRawEventListener;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.eventstream.control.EventsQueryProbe;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * <b>What qits-ci asks qits-events, and why</b> (qits-1149): the one table that both {@code
 * EventsConsumerPactTest} (each row against a pact mock server) and {@code EventsPactFileTest} (the
 * committed {@code pacts/qits-ci-service_qits-events-service.json}) are built from.
 *
 * <p>qits-ci does not call qits-events itself. The qits-eventstream library does, for each durable
 * listener qits-ci declares: its catch-up sweep reads {@code GET /events/api/events} ({@code
 * listEvents}) once to find where a new consumer starts ({@code CatchupSweeper.initialize}, skipped
 * by a listener that replays from the start) and then page by page ({@code CatchupSweeper.catchUp}).
 * The names each listener asks for are read off the listener itself, so the pact follows the code.
 *
 * <p>qits-events records two states. {@code a few recent events} holds three events of names no
 * qits-ci listener asks for by name, so only the listener that takes every event can be pacted
 * against it; every other row reads {@code no events}, whose answer is an empty page. The library
 * reads {@code id}, {@code name}, {@code occurredAt} and {@code payload} of each event (the cursor
 * is built from {@code occurredAt} and {@code id}); {@code nextCursor} is absent from both
 * recordings, which the library reads as "last page".
 *
 * <p>Not here: {@code PUT /events/api/events/{id}} (the outbox's publish), which qits-events has
 * no provider state for yet — see {@link PendingContracts}.
 */
final class EventsContract {

  static final String A_FEW_RECENT_EVENTS = "a few recent events";
  static final String NO_EVENTS = "no events";
  static final String LIST_EVENTS = "listEvents";

  /** What the library reads of each event in a page. */
  static final Set<String> FRAME_FIELDS =
      Set.of("$.events[*].id", "$.events[*].name", "$.events[*].occurredAt", "$.events[*].payload");

  /** An empty page: the whole (empty) list is what the library reads. */
  static final Set<String> PAGE = Set.of("$.events");

  /** What the row does with the library's client, asserting what that code path makes of it. */
  @FunctionalInterface
  interface Call {
    void run(String eventsUrl);
  }

  record Case(
      Trigger trigger, String state, Map<String, String> query, Set<String> consumes, Call call) {

    String description() {
      return Trigger.description(LIST_EVENTS, trigger);
    }
  }

  static final List<Case> CASES = cases();

  private EventsContract() {}

  private static List<Case> cases() {
    List<Case> cases = new ArrayList<>();
    for (QitsDurableEventListener listener : listeners()) {
      Set<String> names = listener.signatures();
      boolean everything = names.contains(QitsRawEventListener.ALL);
      if (!listener.replayFromEpoch()) {
        cases.add(
            new Case(
                Trigger.schedule("CatchupSweeper.initialize(" + listener.consumerId() + ")"),
                NO_EVENTS,
                newestQuery(names),
                PAGE,
                url -> assertNull(EventsQueryProbe.newest(url, names), "no events: no newest")));
      }
      cases.add(
          new Case(
              Trigger.schedule("CatchupSweeper.catchUp(" + listener.consumerId() + ")"),
              everything ? A_FEW_RECENT_EVENTS : NO_EVENTS,
              afterQuery(names),
              everything ? FRAME_FIELDS : PAGE,
              everything ? EventsContract::readsEveryRecordedEvent : url -> readsAnEmptyPage(url, names)));
    }
    return List.copyOf(cases);
  }

  /** Every durable listener qits-ci declares. A new one fails {@code EventsPactFileTest}. */
  static List<QitsDurableEventListener> listeners() {
    return List.of(
        new CiEventTriggerListener(),
        new ScmReleaseListener(),
        new BuildSuccessfulListener(),
        new RepositoryRenamedListener());
  }

  /** {@code EventsQuery.newest}'s query: {@code limit=1}, then the names unless it takes all. */
  private static Map<String, String> newestQuery(Set<String> names) {
    Map<String, String> query = new LinkedHashMap<>();
    query.put("limit", "1");
    names(names).ifPresent(joined -> query.put("name", joined));
    return query;
  }

  /** {@code EventsQuery.after}'s first page: ascending, the sweep's page size, the names. */
  private static Map<String, String> afterQuery(Set<String> names) {
    Map<String, String> query = new LinkedHashMap<>();
    query.put("order", "asc");
    query.put("limit", Integer.toString(EventsQueryProbe.pageSize()));
    names(names).ifPresent(joined -> query.put("name", joined));
    return query;
  }

  private static java.util.Optional<String> names(Set<String> names) {
    if (names.contains(QitsRawEventListener.ALL)) {
      return java.util.Optional.empty();
    }
    StringJoiner joined = new StringJoiner(",");
    new LinkedHashSet<>(names).forEach(joined::add);
    return java.util.Optional.of(joined.toString());
  }

  private static void readsEveryRecordedEvent(String url) {
    EventsQueryProbe.Page page = EventsQueryProbe.after(url, Set.of(QitsRawEventListener.ALL), null);
    JsonNode recorded = EventsGoldenMasters.json(A_FEW_RECENT_EVENTS, LIST_EVENTS).path("events");
    assertEquals(recorded.size(), page.events().size(), "one frame per recorded event");
    // The pact answers with one template repeated (a "contains at least n" list), built from the
    // first recorded event: every frame must read as that event.
    JsonNode event = recorded.get(0);
    for (EventFrame frame : page.events()) {
      assertEquals(event.path("id").asText(), frame.id());
      assertEquals(event.path("name").asText(), frame.name());
      assertEquals(event.path("occurredAt").asText(), frame.occurredAt().toString());
      assertEquals(event.path("payload").asText(), frame.payload());
    }
    assertNull(page.nextCursor(), "the recording is one page");
  }

  private static void readsAnEmptyPage(String url, Set<String> names) {
    EventsQueryProbe.Page page = EventsQueryProbe.after(url, names, null);
    assertTrue(page.events().isEmpty(), "no events: an empty page");
    assertNull(page.nextCursor(), "an empty page is the last");
  }

  static V4Pact pact() {
    return pact(CASES);
  }

  static V4Pact pact(List<Case> cases) {
    PactBuilder builder =
        new PactBuilder(
            EventsGoldenMasters.CONSUMER, EventsGoldenMasters.PROVIDER, PactSpecVersion.V4);
    for (Case c : cases) {
      EventsGoldenMasters.interaction(
          builder, c.state(), LIST_EVENTS, c.trigger(), c.query(), c.consumes());
    }
    return builder.toPact();
  }
}
