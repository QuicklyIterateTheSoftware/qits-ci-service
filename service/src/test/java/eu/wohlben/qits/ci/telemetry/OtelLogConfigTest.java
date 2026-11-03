package eu.wohlben.qits.ci.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

/**
 * The drift guard on this service's OTel log export. It reads the config a run actually boots with
 * and pins the keys that decide whether an {@code org.jboss.logging.Logger} call (and a span, and a
 * metric) leaves this process: the four {@code quarkus.otel.logs.*} values, the {@code cdi}
 * exporter of each signal, and qits-telemetry-core's {@code qits.telemetry.endpoint}.
 *
 * <p>This test asserts CONFIGURATION and nothing else. That the keys really produce exports — all
 * three signals, each carrying the {@code qits} client's bearer — is proven once, in qits-events:
 * {@code OtelLogBridgeTest} decodes a real {@code ExportLogsServiceRequest} from the running handler
 * and {@code PackagedLogBridgeIT} repeats the claim against the packaged artifact. This service
 * runs the same {@code quarkus-opentelemetry} extension on the same four keys, so the behaviour is
 * inherited rather than re-measured, and what is left to lose here is the configuration — a Quarkus
 * upgrade changing a default (the OTel logging integration is still PREVIEW), or a property edit,
 * would otherwise stop this service's logging with a green build and no error anywhere.
 *
 * <p>The values come from {@code src/main/resources/application.properties}, which the suite
 * inherits rather than shadowing, so this is the shipped config under test. Note the suite runs
 * with {@code quarkus.otel.sdk.disabled=true} under {@code %test} — a suite dials nothing — which
 * is exactly why the keys below are read rather than exercised.
 */
@QuarkusTest
class OtelLogConfigTest {

  private static String value(String key) {
    Config config = ConfigProvider.getConfig();
    return config.getValue(key, String.class);
  }

  @Test
  void logExportIsOnAndRidesTheConfiguredExporter() {
    // enabled turns the signal on; handler.enabled is the JBoss Log Manager handler that makes the
    // records; cdi routes them at the exporter configured below rather than a second one. All three
    // are Quarkus' own defaults, which is precisely why they are pinned.
    assertEquals("true", value("quarkus.otel.logs.enabled"));
    assertEquals("true", value("quarkus.otel.logs.handler.enabled"));
    assertEquals("cdi", value("quarkus.otel.logs.exporter"));
  }

  @Test
  void theOutboundFloorIsInfo() {
    // The one value that is not the shipped default: Quarkus exports everything the log manager
    // creates (ALL), and this narrows what leaves the process to a deliberate amount.
    assertEquals("INFO", value("quarkus.otel.logs.level"));
  }

  @Test
  void everySignalGoesThroughTheCdiExporters() {
    // qits-telemetry-core's beans are the exporters (epic qits-428): `cdi` is what makes the SDK
    // take them. Quarkus' default for all three, pinned for the reason the logs keys are.
    assertEquals("cdi", value("quarkus.otel.traces.exporter"));
    assertEquals("cdi", value("quarkus.otel.logs.exporter"));
    assertEquals("true", value("quarkus.otel.metrics.enabled"));
    assertEquals("cdi", value("quarkus.otel.metrics.exporter"));
  }

  @Test
  void theBearerIsOnForTheNamedQitsClient() {
    // The library's defaults, not overridden here: unlike every other QITS_DOMAIN-derived key in
    // this file, qits.telemetry.endpoint has no %test override — this repo's convention is that
    // neither profile needs a domain, and with the SDK disabled below the endpoint is never
    // resolved at runtime. So this test pins the two keys that do NOT expand QITS_DOMAIN, rather
    // than forcing that resolution the way a single-module service's suite can afford to.
    assertEquals("true", value("qits.telemetry.bearer.enabled"));
    assertEquals("qits", value("qits.telemetry.bearer.client"));
    // The wire-alias address is gone with Quarkus' own exporter, which was all that read it.
    assertTrue(
        ConfigProvider.getConfig()
            .getOptionalValue("qits.observability.url", String.class)
            .isEmpty(),
        "qits.observability.url reaches no exporter and must not come back");
  }
}
