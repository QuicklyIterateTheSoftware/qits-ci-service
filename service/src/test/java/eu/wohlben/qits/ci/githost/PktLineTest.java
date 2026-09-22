package eu.wohlben.qits.ci.githost;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The framing, on bytes — including the four ways it can be wrong.
 *
 * <p>{@link HttpGitConfigSourceTest} proves the whole read against a real git host, which is what
 * says the parser agrees with what git really writes. What that cannot stage is a malformed
 * advertisement: the bytes arrive from another service over a network, and every way they can be
 * broken has to end the walk rather than throw or invent a ref. Pure and static, so it is stated
 * here as data.
 */
public class PktLineTest {

  private static byte[] pkt(String... payloads) {
    StringBuilder body = new StringBuilder();
    for (String payload : payloads) {
      body.append(String.format("%04x", payload.getBytes(StandardCharsets.UTF_8).length + 4))
          .append(payload);
    }
    return body.toString().getBytes(StandardCharsets.UTF_8);
  }

  @Test
  public void theServiceLineAndTheFlushPacketsAreNotRefs() {
    byte[] body =
        concat(
            pkt("# service=git-upload-pack\n"),
            "0000".getBytes(StandardCharsets.UTF_8),
            pkt("a".repeat(40) + " refs/heads/main\0side-band-64k\n"),
            "0000".getBytes(StandardCharsets.UTF_8));

    assertEquals(
        List.of("a".repeat(40) + " refs/heads/main\0side-band-64k"), PktLine.refLines(body));
  }

  @Test
  public void everyRefLineComesBackInOrderAndVerbatim() {
    byte[] body =
        pkt(
            "a".repeat(40) + " refs/tags/2026.922.161358\n",
            "b".repeat(40) + " refs/tags/2026.922.161358^{}\n");

    assertEquals(
        List.of(
            "a".repeat(40) + " refs/tags/2026.922.161358",
            "b".repeat(40) + " refs/tags/2026.922.161358^{}"),
        PktLine.refLines(body),
        "where a ref name ends is the caller's business; this class only frames");
  }

  @Test
  public void aLengthThatIsNotHexEndsTheWalkRatherThanResynchronising() {
    // A parser that scanned forward looking for the next plausible frame would invent refs out of
    // the middle of a payload. What has been read stands; the rest is not guessed at.
    byte[] body = concat(pkt("a".repeat(40) + " refs/tags/2026.922.161358\n"), "zzzz junk".getBytes(StandardCharsets.UTF_8));

    assertEquals(List.of("a".repeat(40) + " refs/tags/2026.922.161358"), PktLine.refLines(body));
  }

  @Test
  public void aTruncatedAnswerEndsTheWalkAndNeverThrows() {
    // A length promising more bytes than are there — a response cut off mid-frame. The caller reads
    // a short answer as a short answer, never as an exception on the trigger worker.
    byte[] body = "0040 not forty bytes".getBytes(StandardCharsets.UTF_8);

    assertEquals(List.of(), PktLine.refLines(body));
  }

  @Test
  public void aLengthBelowItsOwnFourBytesIsRefused() {
    // 0003 is not a marker and not a frame; taking it at face value would read a negative payload.
    assertEquals(List.of(), PktLine.refLines("0003x".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  public void nothingAtAllIsNoLinesRatherThanAFailure() {
    assertEquals(List.of(), PktLine.refLines(null));
    assertEquals(List.of(), PktLine.refLines(new byte[0]));
    assertEquals(List.of(), PktLine.refLines("0000".getBytes(StandardCharsets.UTF_8)));
  }

  private static byte[] concat(byte[]... parts) {
    int length = 0;
    for (byte[] part : parts) {
      length += part.length;
    }
    byte[] all = new byte[length];
    int at = 0;
    for (byte[] part : parts) {
      System.arraycopy(part, 0, all, at, part.length);
      at += part.length;
    }
    return all;
  }
}
