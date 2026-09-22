package eu.wohlben.qits.ci.githost;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The four bytes of git's wire framing, and nothing else.
 *
 * <p>A pkt-line is a four-character hexadecimal length — <b>including those four characters</b> —
 * followed by that many bytes minus four of payload. {@code 0000} is a flush packet with no payload
 * and {@code 0001}/{@code 0002} are delimiters; all three are markers rather than content. That is
 * the whole format, and it is why the git host's ref advertisement can be read here without a git
 * library: {@code HttpGitConfigSource.readTags} asks the smart-HTTP route every clone already uses
 * and needs the ref lines out of the answer.
 *
 * <p><b>Hand-rolled rather than depended on</b>, the reason every client in this repository is: a
 * dependency is a decision about what the native-image builder has to be told, and thirty lines of
 * hex arithmetic need told nothing. Pure and static, so it is exhaustively testable with no socket.
 *
 * <p><b>Hostile to nothing and trusting of nothing.</b> The bytes come off another service over a
 * network, so every way they can be wrong ends the read rather than throwing: a truncated length, a
 * length that is not hex, a length longer than what is left, and a length below the four bytes it
 * counts itself all stop the walk and answer what was already read. A caller reads a short answer as
 * a short answer; it never reads an exception.
 */
final class PktLine {

  /** The first non-marker line of an advertisement, which HTTP prefixes and a bare stream does not. */
  private static final String SERVICE_LINE = "# service=";

  private PktLine() {}

  /**
   * The ref lines of a smart-HTTP ref advertisement, as text with trailing newlines stripped.
   *
   * <p>What is dropped is exactly what is not a ref: the {@code # service=…} line the HTTP transport
   * prefixes (the route may or may not write it — JGit's does — and a caller must not care), every
   * flush and delimiter packet, and blank payloads. What is kept is handed back verbatim, {@code
   * \0capabilities} included, because deciding where a ref name ends is the caller's business and
   * this class's whole job is framing.
   *
   * <p>Protocol v2 is deliberately not handled: this reads what the host answers when nothing asks
   * for v2, which is the v0 advertisement, and a {@code version 2} line would simply be a line with
   * no {@code refs/} in it and be ignored by the caller.
   */
  static List<String> refLines(byte[] body) {
    List<String> lines = new ArrayList<>();
    if (body == null) {
      return lines;
    }
    int at = 0;
    while (at + 4 <= body.length) {
      int length = hex(body, at);
      if (length < 0) {
        // Not a length at all: the answer is not an advertisement (or not any more), so stop rather
        // than resynchronise — a parser that guesses where the next frame starts invents refs.
        return lines;
      }
      if (length == 0 || length == 1 || length == 2) {
        // flush-pkt and the two delimiters: markers, no payload, and the advertisement continues.
        at += 4;
        continue;
      }
      if (length < 4 || at + length > body.length) {
        return lines;
      }
      String payload =
          new String(body, at + 4, length - 4, StandardCharsets.UTF_8).stripTrailing();
      at += length;
      if (payload.isEmpty() || payload.startsWith(SERVICE_LINE)) {
        continue;
      }
      lines.add(payload);
    }
    return lines;
  }

  /** The four-hex-digit length at {@code at}, or -1 when those bytes are not four hex digits. */
  private static int hex(byte[] body, int at) {
    int value = 0;
    for (int i = at; i < at + 4; i++) {
      int digit = Character.digit((char) (body[i] & 0xff), 16);
      if (digit < 0) {
        return -1;
      }
      value = value * 16 + digit;
    }
    return value;
  }
}
