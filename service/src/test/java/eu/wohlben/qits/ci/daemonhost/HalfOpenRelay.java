package eu.wohlben.qits.ci.daemonhost;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A byte-for-byte TCP relay standing in for the platform edge's splice (qits-1728): every accepted
 * connection is a <em>daemon leg</em>, spliced to a fresh <em>host leg</em> dialled to the target.
 *
 * <p>What makes it the edge rather than a proxy is what it does NOT do: one leg ending never ends the
 * other. A read hitting EOF stops pumping that direction, and a write failing on a dead leg is
 * swallowed — no FIN, no RST, no close travels across. {@link Splice#cutDaemonLeg} then reproduces
 * the incident exactly: the daemon sees its socket close, while the host's socket stays open and
 * hears nothing, as it does behind the edge for its whole idle timeout.
 */
final class HalfOpenRelay implements AutoCloseable {

  private final ServerSocket listener;
  private final InetSocketAddress target;
  private final List<Splice> splices = new CopyOnWriteArrayList<>();

  /** One daemon leg and the host leg spliced to it. */
  static final class Splice {
    private final Socket daemonLeg;
    private final Socket hostLeg;

    private Splice(Socket daemonLeg, Socket hostLeg) {
      this.daemonLeg = daemonLeg;
      this.hostLeg = hostLeg;
    }

    /** Kill the daemon's leg only; the host's leg is left open and silent. */
    void cutDaemonLeg() {
      closeQuietly(daemonLeg);
    }

    boolean hostLegOpen() {
      return !hostLeg.isClosed();
    }
  }

  HalfOpenRelay(URI target) throws IOException {
    this.target = new InetSocketAddress(target.getHost(), target.getPort());
    this.listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    Thread.ofVirtual().name("relay-accept").start(this::accept);
  }

  /** The address a daemon dials instead of the host's, same path. */
  URI endpoint(URI original) {
    return URI.create(
        original.getScheme()
            + "://"
            + listener.getInetAddress().getHostAddress()
            + ":"
            + listener.getLocalPort()
            + original.getPath());
  }

  /** The splices made so far, oldest first. */
  List<Splice> splices() {
    return List.copyOf(splices);
  }

  private void accept() {
    while (!listener.isClosed()) {
      try {
        Socket daemonLeg = listener.accept();
        Socket hostLeg = new Socket();
        hostLeg.connect(target, 5_000);
        splices.add(new Splice(daemonLeg, hostLeg));
        pump(daemonLeg, hostLeg);
        pump(hostLeg, daemonLeg);
      } catch (IOException e) {
        return;
      }
    }
  }

  private static void pump(Socket from, Socket to) {
    Thread.ofVirtual()
        .name("relay-pump")
        .start(
            () -> {
              byte[] buffer = new byte[8192];
              try {
                InputStream in = from.getInputStream();
                OutputStream out = to.getOutputStream();
                int read;
                while ((read = in.read(buffer)) >= 0) {
                  out.write(buffer, 0, read);
                  out.flush();
                }
              } catch (IOException e) {
                // A dead leg. Deliberately nothing is passed on to the other one.
              }
            });
  }

  private static void closeQuietly(Socket socket) {
    try {
      socket.close();
    } catch (IOException ignored) {
      // gone already
    }
  }

  @Override
  public void close() {
    closeQuietly(listener);
    for (Splice splice : splices) {
      closeQuietly(splice.daemonLeg);
      closeQuietly(splice.hostLeg);
    }
  }

  private static void closeQuietly(ServerSocket socket) {
    try {
      socket.close();
    } catch (IOException ignored) {
      // gone already
    }
  }
}
