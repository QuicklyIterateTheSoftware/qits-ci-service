package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * <b>No recipe this repository ships rewrites a lockfile</b> (qits-731): not a packaged archetype,
 * not a platform pipeline, and not this repository's own {@code release.yml}.
 *
 * <p>Every npm step used to {@code sed -i} the origins of its lockfile's {@code resolved} URLs,
 * because lockfiles were generated on a host that named internal services. They are committed
 * resolving against the platform's public registries now, the composed prelude refuses one that
 * does not ({@link CiReleaseComposer#lockfileOriginCheck()}), and a rewrite coming back would hide
 * exactly the lockfile that check exists to name — the install would go green on a file nobody can
 * install from anywhere else.
 *
 * <p><b>What counts as a rewrite, stated exactly.</b> A shell command — its continuation lines
 * joined, comment lines dropped — that runs {@code sed -i} and mentions {@code resolved}, or that
 * calls the CLI's {@code rewrite-lockfile-origin}. A {@code sed} that only READS {@code resolved}
 * out of a pipe (the maintenance bump's foreign-origin report does) edits nothing and is not one.
 * Text, not behaviour, like {@code PackagedReleaseArchetypesTest}'s credential rules; the fixture
 * case below is what keeps the matcher honest against the form it replaced.
 */
public class NoLockfileRewriteTest {

  private static final Path CONFIG = Path.of("..", ".config", "qits");

  /** The form every recipe carried until qits-731, verbatim: the matcher must see it. */
  private static final String THE_RETIRED_REWRITE =
      """
          if [ -f package-lock.json ]; then
            sed -i -E \\
              -e "s#(\\"resolved\\": \\")https?://[^/\\"]+#\\1$npm_proxy_origin#" \\
              -e "s#(\\"resolved\\": \\")https?://[^/\\"]+($npm_hosted_path)#\\1$npm_hosted_origin\\2#" \\
              package-lock.json
          fi
      """;

  private static List<Path> recipes() throws Exception {
    List<Path> files = new ArrayList<>();
    for (String dir : List.of("release-archetypes", "platform-pipelines")) {
      try (Stream<Path> listed = Files.list(CONFIG.resolve(dir))) {
        listed.filter(path -> path.toString().endsWith(".yml")).sorted().forEach(files::add);
      }
    }
    files.add(CONFIG.resolve("release.yml"));
    return files;
  }

  /** Every logical shell command in {@code text} that rewrites a lockfile. */
  static List<String> rewrites(String text) {
    List<String> commands = new ArrayList<>();
    StringBuilder command = new StringBuilder();
    for (String line : text.split("\n", -1)) {
      String trimmed = line.strip();
      if (trimmed.startsWith("#")) {
        continue;
      }
      command.append(trimmed).append(' ');
      if (trimmed.endsWith("\\")) {
        continue;
      }
      String joined = command.toString();
      command.setLength(0);
      boolean sedInPlace = joined.matches(".*\\bsed\\b.*\\s-i\\b.*");
      if ((sedInPlace && joined.contains("resolved"))
          || joined.contains("rewrite-lockfile-origin")) {
        commands.add(joined.strip());
      }
    }
    return commands;
  }

  @Test
  public void theMatcherSeesTheRewriteItRetired() {
    assertEquals(1, rewrites(THE_RETIRED_REWRITE).size(), THE_RETIRED_REWRITE);
    assertEquals(
        1, rewrites("      qits-publish npm rewrite-lockfile-origin package-lock.json\n").size());
  }

  @Test
  public void theMatcherLeavesAReadAlone() {
    // The maintenance bump's report of foreign origins: a pipe that reads, and edits no file.
    assertEquals(
        List.of(),
        rewrites(
            """
                foreign=$(grep -o '"resolved": *"[^"]*"' "$lock" \\
                  | sed -e 's/^"resolved": *"//' -e 's/"$//' \\
                  | sort -u)
            """));
  }

  @Test
  public void noShippedRecipeRewritesALockfile() throws Exception {
    List<Path> files = recipes();
    assertTrue(files.size() > 9, "the archetypes, the platform pipelines and release.yml: " + files);
    List<String> found = new ArrayList<>();
    for (Path file : files) {
      for (String command : rewrites(Files.readString(file, StandardCharsets.UTF_8))) {
        found.add(file.getFileName() + ": " + command);
      }
    }
    assertEquals(
        List.of(),
        found,
        "a lockfile is installed as committed and never rewritten (qits-731) — commit it resolving"
            + " against the platform's public registries instead");
  }
}
