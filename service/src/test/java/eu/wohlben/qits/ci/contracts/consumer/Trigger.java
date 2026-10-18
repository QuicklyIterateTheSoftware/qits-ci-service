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

  /** This repository, as every interaction's {@code qits-trigger} names it. */
  public static final String CONSUMER = "qits-ci-service";

  public Trigger {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(app, "app");
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(value, "value");
  }

  public static Trigger operation(String operationId) {
    return new Trigger("operation", CONSUMER, "operationId", operationId);
  }

  public static Trigger event(String eventType) {
    return new Trigger("event", CONSUMER, "event", eventType);
  }

  public static Trigger schedule(String schedule) {
    return new Trigger("schedule", CONSUMER, "schedule", schedule);
  }

  /** The {@code qits-trigger} group, all values strings, in a fixed key order. */
  public Map<String, String> reference() {
    Map<String, String> ref = new LinkedHashMap<>();
    ref.put("kind", kind);
    ref.put("app", app);
    ref.put(key, value);
    return ref;
  }
}
