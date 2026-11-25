package eu.wohlben.qits.ci.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A repository's release requests for the ci suite: whatever the test says, and no HTTP. The
 * production implementation is {@code service/…/projects/HttpProjectsReleaseRequestListing} — this
 * module has none, so this fake is also what makes the port resolvable here at all, exactly as
 * {@link FakeProjectsRepoListing} is for the catalogue.
 *
 * <p><b>Unconfigured is the default, and it is the arm every existing test is on</b>: no
 * qits-projects in this deployment, so the pact parity check reads the counterpart at its release
 * alone, as it did before qits-2604. A test that wants open requests calls {@link #set}; one that
 * wants the read to fail calls {@link #unreachable}.
 */
@ApplicationScoped
public class FakeReleaseRequestListing implements CiReleaseRequestListing {

  // Read through methods: a field read on an injected CDI client proxy sees the proxy's fields.
  private final List<Request> requests = new ArrayList<>();
  private final List<String> asked = new CopyOnWriteArrayList<>();
  private volatile boolean configured;
  private volatile String unreachable;

  /** Configures this listing and seeds what it answers, for every repository asked. */
  public void set(Request... answered) {
    configured = true;
    unreachable = null;
    requests.clear();
    requests.addAll(List.of(answered));
  }

  /** Configured, but every read answers that it could not be made. */
  public void unreachable(String why) {
    configured = true;
    unreachable = why;
    requests.clear();
  }

  /** Back to unset: no qits-projects in this deployment. */
  public void unset() {
    configured = false;
    unreachable = null;
    requests.clear();
    asked.clear();
  }

  /** Every repository id a read was made for, in order. */
  public List<String> asked() {
    return List.copyOf(asked);
  }

  @Override
  public boolean configured() {
    return configured;
  }

  @Override
  public Listing releaseRequests(String repoId) {
    asked.add(repoId);
    return unreachable != null ? Listing.unreachable(unreachable) : Listing.of(requests);
  }
}
