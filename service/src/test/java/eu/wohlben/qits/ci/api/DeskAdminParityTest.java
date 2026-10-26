package eu.wohlben.qits.ci.api;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import eu.wohlben.qits.archrules.DeskAdminParityRules;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * qits-1631: {@code qits:desk-admin} must reach every door {@code qits:admin-agent} reaches in
 * this service, with no exceptions — the epic qits-1621 dossier page "Refusals" lists no refusal
 * for qits-ci-service at all ("Other services: They have no refusals: every admin-agent route
 * admits the desk-admin"), unlike qits-projects-service, qits-workspaces-service and
 * qits-githost-service, which each carry their own doors the desk keeps to itself.
 *
 * <p>Imports {@code eu.wohlben.qits.ci.api} with a plain {@code ClassFileImporter} rather than
 * {@code @AnalyzeClasses}, the way {@code entities/ArchRulesTest} in qits-projects-service does for
 * {@code DeskAdminParityRules} — the library's own javadoc shows the {@code @AnalyzeClasses}/{@code
 * @ArchTest} shape too, and either reaches the same classes; this repository's existing {@code
 * ArchRulesTest} in this module already analyzes {@code eu.wohlben.qits.ci} with {@code
 * @AnalyzeClasses}, so importing explicitly here keeps the two rule sets' wiring visibly distinct
 * rather than folding a second, unrelated rule onto that class.
 */
class DeskAdminParityTest {

  /**
   * No door here is refused the role: see this class's javadoc. Four files carry {@code
   * qits:admin-agent} — {@code CiReportController}, {@code CiEventController}, {@code
   * CiRunController} and {@code CiRepositoryController} — and qits-1631 added {@code
   * qits:desk-admin} beside it everywhere it appears.
   */
  private static final Set<String> REFUSED = Set.of();

  @Test
  void everyAdminAgentRouteAdmitsDeskAdmin() {
    ArchRule rule = DeskAdminParityRules.everyAdminAgentRouteAdmitsDeskAdmin(REFUSED);
    rule.check(new ClassFileImporter().importPackages("eu.wohlben.qits.ci.api"));
  }

  @Test
  void refusedRoutesExistAndRefuse() {
    ArchRule rule = DeskAdminParityRules.refusedRoutesExistAndRefuse(REFUSED);
    rule.check(new ClassFileImporter().importPackages("eu.wohlben.qits.ci.api"));
  }
}
