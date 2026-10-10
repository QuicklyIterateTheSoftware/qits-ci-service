package eu.wohlben.qits.ci.contracts.consumer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What made qits-ci make a call: the {@code qits-trigger} reference every interaction carries
 * (epic qits-546). One of four kinds, each naming the entry point by its own key: {@code operation}
 * (qits-ci's own REST door), {@code event} (a bus event), {@code schedule} (a scheduled or
 * background job), {@code ui} (an interaction in some app). qits-ci's REST doors mostly carry no
 * {@code operationId}, so an {@code operation} trigger names the door's method instead.
 */
public record Trigger(String kind, String app, String key, String value) {

  public Trigger {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(app, "app");
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(value, "value");
  }

  public static Trigger operation(String operationId) {
    return new Trigger("operation", EventsGoldenMasters.CONSUMER, "operationId", operationId);
  }

  public static Trigger event(String eventType) {
    return new Trigger("event", EventsGoldenMasters.CONSUMER, "event", eventType);
  }

  public static Trigger schedule(String schedule) {
    return new Trigger("schedule", EventsGoldenMasters.CONSUMER, "schedule", schedule);
  }

  /** The {@code qits-trigger} group, all values strings, in a fixed key order. */
  public Map<String, String> reference() {
    Map<String, String> ref = new LinkedHashMap<>();
    ref.put("kind", kind);
    ref.put("app", app);
    ref.put(key, value);
    return ref;
  }

  /** An interaction's description: the trigger first, so (description, state) stays unique. */
  public static String description(String operationId, Trigger trigger) {
    return trigger.value() + ": " + operationId;
  }
}
