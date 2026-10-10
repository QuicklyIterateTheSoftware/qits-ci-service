package eu.wohlben.qits.eventstream.control;

import java.util.List;
import java.util.Set;

/**
 * Opens the qits-eventstream library's own {@link EventsQuery} to qits-ci's consumer pact test
 * (qits-1149). The two reads the catch-up sweep makes are package-private in the library, so this
 * helper sits in the library's package, on qits-ci's test classpath only. It calls the real client
 * with the real page size, so the pact holds the request the library actually sends.
 */
public final class EventsQueryProbe {

  /** One page as the sweep sees it: the rows and the cursor of the next page, if any. */
  public record Page(List<EventFrame> events, String nextCursor) {}

  private EventsQueryProbe() {}

  /** {@code CatchupSweeper.catchUp}'s read: the first page after {@code cursor} (null: the start). */
  public static Page after(String eventsUrl, Set<String> names, String cursor) {
    EventPage page = query(eventsUrl).after(names, cursor, CatchupSweeper.PAGE_SIZE);
    return new Page(page.events(), page.nextCursor());
  }

  /** {@code CatchupSweeper.initialize}'s read: the newest event of {@code names}, or null. */
  public static EventFrame newest(String eventsUrl, Set<String> names) {
    return query(eventsUrl).newest(names);
  }

  /** The library's page size, which the request's {@code limit} carries. */
  public static int pageSize() {
    return CatchupSweeper.PAGE_SIZE;
  }

  private static EventsQuery query(String eventsUrl) {
    EventsQuery query = new EventsQuery();
    query.eventsUrl = eventsUrl;
    return query;
  }
}
