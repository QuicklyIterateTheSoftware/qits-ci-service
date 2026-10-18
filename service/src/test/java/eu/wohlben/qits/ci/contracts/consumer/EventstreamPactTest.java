package eu.wohlben.qits.ci.contracts.consumer;

import eu.wohlben.qits.ci.bus.BuildSuccessfulListener;
import eu.wohlben.qits.ci.bus.CiEventTriggerListener;
import eu.wohlben.qits.ci.bus.RepositoryRenamedListener;
import eu.wohlben.qits.ci.bus.ScmReleaseListener;
import eu.wohlben.qits.eventstream.contracts.EventstreamPact;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * <b>What qits-eventstream asks qits-events on qits-ci's behalf</b> (qits-1163): each durable
 * listener's catch-up reads and the outbox publish of BuildAnnouncer, SoftwareReleaseAnnouncer and
 * RunnerLifecycleAnnouncer, pacted by this service against the library version it pins and
 * committed as {@code pacts/qits-ci-service_qits-eventstream-javalib_qits-events-service.json}.
 * The rows come from the library's kit; the guard fails naming any listener or publisher in {@code
 * target/classes} left out here.
 */
class EventstreamPactTest {

  static final EventstreamPact PACT =
      EventstreamPact.forConsumer("qits-ci-service")
          .listeners(
              new BuildSuccessfulListener(),
              new CiEventTriggerListener(),
              new RepositoryRenamedListener(),
              new ScmReleaseListener())
          .publishes(true)
          .guard(Path.of("target/classes"));

  @TestFactory
  Stream<DynamicTest> rows() {
    return PACT.tests();
  }

  @Test
  void pactFile() {
    PACT.compareOrWritePactFile();
  }
}
