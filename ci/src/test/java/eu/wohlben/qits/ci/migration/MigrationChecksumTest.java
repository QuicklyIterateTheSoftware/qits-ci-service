package eu.wohlben.qits.ci.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * <b>Never edit an applied migration — the rule, made mechanical.</b> Every file in {@code
 * db/ci/migration/} is pinned here by the SHA-256 of its bytes, so changing one is a red build in
 * this repository instead of a rollback in the deployment.
 *
 * <p><b>It exists because the rule has failed twice, the same way both times, and neither time was
 * anything but comments.</b> Flyway checksums a migration over its WHOLE FILE — the SQL and the
 * prose around it alike — and validates every applied one at boot, so a comment added to V1 is a
 * different V1 to a database that has already run it. On 2026-08-23 the step-timeout change
 * rewrote nine lines of V1's header; release 2026.823.164332 would not start and rolled back, and
 * {@code 7bc1dc6} put the bytes back. On 2026-09-17 the daemon-pin-ladder retirement added five
 * lines to the same header saying the table ended in V18; release 2026.917.45603 died before
 * Flyway printed its validate line, the deployment rolled back to 2026.917.40824, the schema
 * stayed at 17 and V18 never ran at all. Both edits were true, both were well meant, and both cost
 * a release — because the checksum does not care what a comment says.
 *
 * <p><b>Why a SHA-256 and not Flyway's own checksum.</b> Flyway's is a CRC32 computed by an
 * internal class this test would have to reach into, and it normalises line endings on the way —
 * so it is both a moving target across upgrades and strictly weaker than byte identity. What the
 * rule wants is that the file does not change, which is what a hash of the bytes says. A pin here
 * therefore fails on edits Flyway would tolerate, and that is the right direction: the answer to
 * "but Flyway would not have minded" is a one-line pin update in the same commit.
 *
 * <p><b>Adding a migration is adding a line to {@link #PINS}, deliberately.</b> An unpinned file
 * fails the test naming itself and its hash, so the pin cannot be forgotten — and until a version
 * has been released, updating its pin beside its content is the ordinary way to work on it. The
 * moment it ships, the pin is what stops the next well-meant comment.
 *
 * <p>It reads the SHIPPED resources off the classpath rather than the source tree, for the reason
 * {@code ArtifactsCliPackageDefaultTest} reads the shipped properties file: the bytes on the
 * classpath are the bytes Flyway runs. Plain JUnit, because nothing here needs an application.
 */
public class MigrationChecksumTest {

  private static final String DIRECTORY = "db/ci/migration";

  /** One entry per file in {@link #DIRECTORY}, SHA-256 of its bytes, in lineage order. */
  private static final Map<String, String> PINS = new LinkedHashMap<>();

  static {
    PINS.put("V1__init.sql", "33c82b8f8b2ffe0fab5598a05caa814a97cd9b52abefff35aa5c14155e4196fc");
    PINS.put(
        "V2__run_causation.sql",
        "d2e69efcf0cc5a6e09c0594ef798ac6502c1ff2679329909745b5391fab81b39");
    PINS.put(
        "V3__release_join.sql",
        "86b922b6219ff48a39efb2ff12546e6c35aee96776ac369e118e2d5a3d0f8ea9");
    PINS.put(
        "V4__run_started_at.sql",
        "6b013a4501ffb64e3c403f33b6dfdede3cb3a630573e13aa73e140f882de5141");
    PINS.put(
        "V5__run_repository_identity.sql",
        "44591d4a0bbbc61fa06ec6a978cfcfbde524f0efe7a8174ee224681eda8d7450");
    PINS.put(
        "V6__timed_out_status_note.sql",
        "247c6c1697100d9f49445981f2c9ceae91de072e51d8051526cb1cdf251f52e6");
    PINS.put(
        "V7__run_gating.sql", "a849d23bf145591883a4359298814104b009fdaa6440bf8849fdebabdf40a7e7");
    PINS.put(
        "V8__run_release_request.sql",
        "8591586240d1abdc9291b6e5e2c9ee72adf65ea68d351049ca18684e1c80161d");
    PINS.put(
        "V9__run_retry.sql", "f649c3ebc142bd87832f8793bb5a61ee6f6a207081c5b652d83c3c4a58b8e22f");
    PINS.put(
        "V10__release_announcement_project.sql",
        "ccc1df46dbc10b2c7667b96bdfb0a9605df9de21f6a8604ded29afd4bab72b20");
    PINS.put(
        "V11__release_announcement_repo_name.sql",
        "e558152509ad5f9b3e9dbd4aa047aecbf54d334469c5b702b11d1194167b8f5e");
    PINS.put(
        "V12__release_announcement_backfill_identity.sql",
        "77e833c04b368b71da19bbd5c129a0c3b7e87315fbb7ffe60060d2c89364f596");
    PINS.put(
        "V13__owed_trigger_event.sql",
        "2298d77f31800cff0fa7602e1630e9e1d3090d64c240cdd0b3fd31f9cc187723");
    PINS.put(
        "V14__scm_release_priority.sql",
        "a4646fc335da4d2a47d0a8f1e230f39176247af872b58686573419388928e411");
    PINS.put(
        "V15__run_ordering_inputs.sql",
        "e4b7f34dd1267f5dd4a2051cfde40bc86fc659783ff7d17da23ab9218f9f95cc");
    PINS.put(
        "V16__run_expected_step_durations.sql",
        "cd0561af8f452f35dd4bc84cfe74bfcf6306804a9955ba5defea57d677e1542e");
    PINS.put(
        "V17__run_phase.sql", "b9d9097af4dc92448fec711e587d15abee6b1f72853efae45db1d9192e1cb59a");
    PINS.put(
        "V18__retire_daemon_pin_ladder.sql",
        "ec9ee9652b2065af25b12dd1c30368cba0c0dafcba11422c1215fab28f8acf99");
    PINS.put(
        "V19__run_drop_gating.sql",
        "a7e59a1ffb694caa689cf67c08958a90f7709a12c6ef6ad45390faad21ee33dc");
    PINS.put(
        "V20__run_archetype.sql",
        "459ac9444f4210e0f69d5836dde0d8de43ef82b878efd113dc5d349b23ad9d02");
    PINS.put(
        "V21__run_step_images.sql",
        "9cf8af098d8688412c80b62d020e70560bb9a151c45e1ff5ae54a93ce0dd19ff");
    PINS.put(
        "V22__run_archetype_version.sql",
        "8e4c5caeb6c66dcec5b3484085fd42a9bbd027459a1d5ea75ae25a5808b51d19");
  }

  @Test
  public void noAppliedMigrationHasBeenEdited() throws Exception {
    for (Map.Entry<String, String> pin : PINS.entrySet()) {
      String file = pin.getKey();
      assertEquals(
          pin.getValue(),
          sha256(read(file)),
          file
              + " has changed. Flyway checksums the whole file, comments included, and validates it"
              + " at boot — so a database that has already run this one will REFUSE TO START"
              + " against the edited bytes, which is how releases 2026.823.164332 and"
              + " 2026.917.45603 rolled back. Put the bytes back and say what you wanted to say in"
              + " the newest migration instead. If this version has genuinely not shipped yet,"
              + " update its pin in MigrationChecksumTest in the same commit.");
    }
  }

  @Test
  public void everyMigrationIsPinned() throws Exception {
    assertEquals(
        new TreeSet<>(PINS.keySet()),
        new TreeSet<>(shippedFileNames()),
        "MigrationChecksumTest.PINS and "
            + DIRECTORY
            + " disagree about which files exist. A new migration is pinned in the commit that adds"
            + " it — an unpinned one is a file the next comment may silently edit.");
  }

  private static byte[] read(String file) throws Exception {
    String resource = DIRECTORY + "/" + file;
    try (InputStream in =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
      assertNotNull(in, resource + " is not on the classpath — the ci jar ships it");
      return in.readAllBytes();
    }
  }

  private static TreeSet<String> shippedFileNames() throws Exception {
    URL anchor =
        Thread.currentThread()
            .getContextClassLoader()
            .getResource(DIRECTORY + "/" + PINS.keySet().iterator().next());
    assertNotNull(anchor, DIRECTORY + " is not on the classpath");
    Path directory = Path.of(anchor.toURI()).getParent();
    assertTrue(Files.isDirectory(directory), directory + " is not a directory");
    TreeSet<String> names = new TreeSet<>();
    try (var entries = Files.list(directory)) {
      entries
          .map(path -> path.getFileName().toString())
          .filter(name -> name.endsWith(".sql"))
          .forEach(names::add);
    }
    return names;
  }

  private static String sha256(byte[] bytes) throws Exception {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
    StringBuilder hex = new StringBuilder(digest.length * 2);
    for (byte b : digest) {
      hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
    }
    return hex.toString();
  }
}
