package eu.wohlben.qits.ci.contracts.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import eu.wohlben.qits.ci.contracts.GoldenFiles;
import eu.wohlben.qits.ci.idp.IdpCommissioner;
import eu.wohlben.qits.ci.projects.HttpProjectsRepoListing;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * <b>Every outbound call qits-ci's code can make has a pact row</b> (qits-1149). The set of calls
 * is derived from the code, not written down: every class under {@code service/src/main/java} that
 * imports an HTTP client — {@code QitsHttpClients}, {@code java.net.http.HttpClient}, {@code
 * OidcClient} or a MicroProfile REST client — is a client of another service, and each of its
 * public instance methods is a call site. Each one must be the {@link PeerContracts.Bound#method} of
 * some row, live or pending, and every row must name a method that exists.
 *
 * <p>So a new client class, or a new public method on one, fails here until it has a row. A method
 * that makes no call is named in {@link #NO_CALL}, with its reason. Not here: the qits-eventstream
 * library's calls to qits-events, which live in the library rather than in this source tree — its
 * catch-up read is {@link EventsContract}, held to the durable listeners by {@code
 * EventsPactFileTest}; its outbox publish is {@link PendingContracts}. This service serves
 * websockets (the runner and daemon sockets) but dials none, and probes no peer's health.
 */
class ClientCallGuardTest {

  private static final List<String> CLIENT_IMPORTS =
      List.of(
          "import eu.wohlben.qits.http.QitsHttpClients;",
          "import java.net.http.HttpClient;",
          "import io.quarkus.oidc.client.OidcClient;",
          "import org.eclipse.microprofile.rest.client.");

  /** Public methods of a client class that make no request, each with its reason. */
  private static final Map<Class<?>, Set<String>> NO_CALL =
      Map.of(
          // reads whether qits.ci.projects-url is set
          HttpProjectsRepoListing.class, Set.of("configured"),
          // reads whether the qits oidc client and its credential are configured
          IdpCommissioner.class, Set.of("enabled"));

  @Test
  void everyClientMethodHasARowAndEveryRowAClientMethod() throws Exception {
    Set<String> inCode = new TreeSet<>();
    for (Class<?> client : clientClasses()) {
      for (Method method : client.getDeclaredMethods()) {
        if (!Modifier.isPublic(method.getModifiers())
            || Modifier.isStatic(method.getModifiers())
            || method.isSynthetic()
            || NO_CALL.getOrDefault(client, Set.of()).contains(method.getName())) {
          continue;
        }
        inCode.add(client.getSimpleName() + "#" + method.getName());
      }
    }
    assertFalse(inCode.isEmpty(), "no client class found under service/src/main/java");

    Set<String> bound = new TreeSet<>();
    for (PeerContracts.Bound row : PeerContracts.ROWS) {
      bound.add(row.client().getSimpleName() + "#" + row.method());
    }
    Set<String> unbound = new TreeSet<>(inCode);
    unbound.removeAll(bound);
    Set<String> stale = new TreeSet<>(bound);
    stale.removeAll(inCode);
    assertEquals(
        Set.of(),
        unbound,
        "client methods with no row in PeerContracts (add one, pending if the provider has no"
            + " state for it)");
    assertEquals(Set.of(), stale, "rows naming a client method the code does not have");
  }

  /** Every main class that imports an HTTP client, loaded by the name its path gives. */
  static List<Class<?>> clientClasses() throws IOException {
    Path root = GoldenFiles.repositoryRoot().resolve("service/src/main/java");
    try (Stream<Path> files = Files.walk(root)) {
      return files
          .filter(f -> f.toString().endsWith(".java"))
          .filter(ClientCallGuardTest::importsAClient)
          .<Class<?>>map(f -> load(root.relativize(f)))
          .toList();
    }
  }

  private static boolean importsAClient(Path file) {
    try {
      String text = Files.readString(file);
      return CLIENT_IMPORTS.stream().anyMatch(text::contains);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Class<?> load(Path relative) {
    String name = relative.toString().replace(".java", "").replace('/', '.');
    try {
      return Class.forName(name, false, ClientCallGuardTest.class.getClassLoader());
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(name, e);
    }
  }
}
