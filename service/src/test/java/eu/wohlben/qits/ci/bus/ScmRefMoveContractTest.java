package eu.wohlben.qits.ci.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.ci.control.CiTriggerListingCache;
import eu.wohlben.qits.eventstream.QitsEvent;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.githost.events.SCMDeleteBranch;
import eu.wohlben.qits.githost.events.SCMDeleteTag;
import eu.wohlben.qits.githost.events.SCMPublishCommit;
import eu.wohlben.qits.githost.events.SCMPublishTag;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * <b>The strings the trigger-listing cache forgets by, checked against the records themselves</b>
 * (qits-168).
 *
 * <p>{@link CiTriggerListingCache} recognises a ref move by event NAME and reads the repository out
 * of a payload FIELD, and both are literals in the {@code ci} module, which cannot name qits-githost's
 * records. A rename upstream would compile, and the cache would quietly stop forgetting — every
 * listing stale until its TTL, after every push. So the guard lives here, in the module that has the
 * jar, the same shape as {@link ScmPublishTagContractTest}: the names are the simple class names, and
 * the field is read off the <b>canonical payload</b>, which is the wire contract.
 */
public class ScmRefMoveContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String REPO_ID = "4c9a985f-0000-4000-8000-000000000001";
  private static final String SHA = "a".repeat(40);
  private static final Instant AT = Instant.parse("2026-10-11T12:00:00Z");

  private static List<QitsEvent> everyRefMove() {
    return List.of(
        new SCMPublishCommit(
            REPO_ID, "qits", "qits-ci-service", "main", "0".repeat(40), SHA, List.of(), "A",
            "a@example.invalid", AT, AT, "message", AT),
        new SCMDeleteBranch(REPO_ID, "qits", "qits-ci-service", "feature", SHA, AT),
        new SCMPublishTag(
            REPO_ID, "qits", "qits-ci-service", "2026.1011.1", SHA, SHA, "A", "a@example.invalid",
            "release", true, AT),
        new SCMDeleteTag(REPO_ID, "qits", "qits-ci-service", "2026.1011.1", SHA, AT));
  }

  @Test
  public void theEventNamesTheCacheForgetsByAreQitsGithostsRefMoveEvents() {
    Set<String> simpleNames =
        everyRefMove().stream().map(e -> e.getClass().getSimpleName()).collect(Collectors.toSet());
    assertEquals(simpleNames, CiTriggerListingCache.REF_MOVE_EVENTS);
  }

  @Test
  public void everyRefMoveCarriesTheStorageIdInTheFieldTheCacheReads() throws Exception {
    for (QitsEvent event : everyRefMove()) {
      JsonNode payload = MAPPER.readTree(CanonicalJson.payload(event));
      assertTrue(
          payload.has(CiTriggerListingCache.REPO_ID_FIELD),
          event.getClass().getSimpleName()
              + "'s canonical payload carries no "
              + CiTriggerListingCache.REPO_ID_FIELD);
      assertEquals(REPO_ID, payload.get(CiTriggerListingCache.REPO_ID_FIELD).asText());
    }
  }
}
