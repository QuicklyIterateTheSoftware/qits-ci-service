package eu.wohlben.qits.ci.contracts;

import eu.wohlben.qits.ci.control.CiCandidateRepos;
import eu.wohlben.qits.ci.control.CiRepoRef;
import eu.wohlben.qits.ci.control.ListedAndKnownCiRepos;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import java.util.List;

/**
 * The trigger engine's candidate repositories for the suite: the shipped {@link
 * ListedAndKnownCiRepos}, unless a provider state pinned a list.
 *
 * <p>A trigger answer counts and names every candidate it read or skipped. The suite shares one
 * database and one stub git host, so without a pin the answer would hold whatever repositories other
 * classes left behind, and the golden master would differ per run. {@link ProviderStates} pins its
 * own repository and {@link ProviderStates#cleanUp} unpins it. Unpinned, every other suite sees the
 * shipped bean unchanged.
 */
@Mock
@ApplicationScoped
@Typed({CiCandidateRepos.class, ContractCandidateRepos.class})
public class ContractCandidateRepos implements CiCandidateRepos {

  @Inject ListedAndKnownCiRepos shipped;

  private volatile List<CiRepoRef> pinned;

  /** Answer exactly these repositories until {@link #unpin()}. */
  public void pin(CiRepoRef... refs) {
    pinned = List.of(refs);
  }

  public void unpin() {
    pinned = null;
  }

  @Override
  public List<CiRepoRef> candidates() {
    List<CiRepoRef> held = pinned;
    return held != null ? held : shipped.candidates();
  }
}
