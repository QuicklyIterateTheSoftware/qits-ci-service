package eu.wohlben.qits.ci;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * qits-ci declares its own configuration keys, and this test holds the fact, not the grammar.
 *
 * <p><b>Why the file exists.</b> qits-configuration flags a stored entry {@code orphaned} only when
 * the application has a declaration that does not list the key. The file is what lets a person see,
 * and remove, the entries qits-ci no longer reads.
 *
 * <p><b>What this test is NOT.</b> It is not a parser. qits-configuration's {@code
 * DeclarationParser} owns the grammar and is the one strict parser of this document; a second one
 * here would disagree with it the day the grammar grows. The document was checked against the real
 * parser, by running it, when it was written. What is worth a standing assertion is what a hand edit
 * can break unseen until a release is refused: the file at the exact path the deployer fetches, the
 * one top-level key, and no key declared twice.
 */
class OwnDeclarationTest {

  /** The deployer's own path, qits-deployments' {@code SpecSource.DECLARATION_PATH}. */
  private static final String DECLARATION_PATH = ".config/qits/configuration.yml";

  /** The document, found by walking up from the directory surefire started this module in. */
  private static Path declaration() {
    Path at = Path.of("").toAbsolutePath();
    for (int up = 0; up < 4 && at != null; up++, at = at.getParent()) {
      Path candidate = at.resolve(DECLARATION_PATH);
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    throw new AssertionError("no " + DECLARATION_PATH + " above " + Path.of("").toAbsolutePath());
  }

  @Test
  void thisRepositoryCarriesItsDeclarationWhereTheDeployerLooksForIt() throws IOException {
    assertFalse(Files.readString(declaration()).isBlank(), "the store refuses an empty document");
  }

  @Test
  void theTopLevelIsTheOneKeyTheStoreAccepts() throws IOException {
    // The store refuses any second top-level key. A stray unindented line is the easiest way to
    // break this file by hand and the hardest to see.
    List<String> topLevel = new ArrayList<>();
    for (String line : Files.readAllLines(declaration())) {
      if (line.isBlank() || line.startsWith("#") || line.startsWith(" ")) {
        continue;
      }
      topLevel.add(line);
    }

    assertEquals(List.of("keys:"), topLevel);
  }

  @Test
  void noKeyIsDeclaredTwice() throws IOException {
    // The store's loader refuses duplicate keys, so a repeated name is a refused release.
    Set<String> seen = new LinkedHashSet<>();
    for (String line : Files.readAllLines(declaration())) {
      String trimmed = line.strip();
      if (!trimmed.startsWith("env.") || !trimmed.endsWith(":")) {
        continue;
      }
      assertTrue(seen.add(trimmed), "declared twice: " + trimmed);
    }

    assertFalse(seen.isEmpty(), "the document declares no keys at all");
  }
}
