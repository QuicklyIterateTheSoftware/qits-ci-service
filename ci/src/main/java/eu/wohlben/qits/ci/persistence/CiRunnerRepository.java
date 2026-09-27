package eu.wohlben.qits.ci.persistence;

import eu.wohlben.qits.ci.entity.CiRunner;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Panache DAO for {@link CiRunner} (keyed by its uuid). Reads only; every write is {@code
 * CiRunners}', which is where the rules about a runner's state live.
 */
@ApplicationScoped
public class CiRunnerRepository implements PanacheRepositoryBase<CiRunner, UUID> {

  /** Every runner, by name — the listing's order, and a stable one. */
  public List<CiRunner> listByName() {
    return list("order by name");
  }

  public Optional<CiRunner> findByName(String name) {
    return find("name", name).firstResultOptional();
  }
}
