package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiContracts.Ecosystem;
import eu.wohlben.qits.ci.control.CiContracts.Kind;
import eu.wohlben.qits.ci.control.CiContracts.Package;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link CiContracts}: the {@code contracts:} grammar, and <b>the coordinate rule pinned against the
 * coordinates the estate consumes</b> (pom dependencies, a package.json, a pact loader's
 * classpath), so a rule change that moves one of them must be a red test here, never a quiet new
 * artifact nobody depends on. Golden masters are named by application, pacts by the two
 * repositories.
 */
public class CiContractsTest {

  private static final String PATH = CiReleaseSlotParser.CONFIG_PATH;

  private final CiReleaseSlotParser parser = new CiReleaseSlotParser();

  private CiContracts contracts(String yaml) {
    return parser.parse(PATH, yaml).contracts();
  }

  private CiConfigException refused(String yaml) {
    return assertThrows(CiConfigException.class, () -> parser.parse(PATH, yaml));
  }

  // --- the live coordinates, exactly --------------------------------------------------------------

  @Test
  public void theLiveCoordinatesAreDerivedExactly() {
    CiContracts projects =
        contracts(
            """
            contracts:
              application: qits-projects
              golden-masters: { from: golden-masters/, packages: [maven, npm] }
            """);
    assertEquals(
        List.of("eu.wohlben.qits:qits-projects-golden-masters", "@qits/projects-golden-masters"),
        projects.packages("qits-projects-service").stream().map(Package::name).toList());

    CiContracts workspaces =
        contracts(
            """
            contracts:
              application: qits-workspaces
              pacts:
                qits-projects-service: { packages: [maven] }
            """);
    assertEquals(
        List.of("eu.wohlben.qits:qits-workspaces-service-pacts-qits-projects-service"),
        workspaces.packages("qits-workspaces-service").stream().map(Package::name).toList());

    CiContracts landing =
        contracts(
            """
            contracts:
              application: qits-landing
              pacts:
                qits-projects-service: { packages: [maven] }
                qits-githost-service: { packages: [maven] }
            """);
    assertEquals(
        List.of(
            "eu.wohlben.qits:qits-landing-app-pacts-qits-projects-service",
            "eu.wohlben.qits:qits-landing-app-pacts-qits-githost-service"),
        landing.packages("qits-landing-app").stream().map(Package::name).toList());
  }

  @Test
  public void theNpmRuleDropsOneQitsPrefixFromTheConsumerAndNoneFromTheProvider() {
    assertEquals(
        "@qits/landing-app-pacts-qits-projects-service",
        CiContracts.coordinate(
            Kind.PACTS, "qits-landing-app", "qits-projects-service", Ecosystem.NPM));
    assertEquals(
        "@qits/qits-x-golden-masters",
        CiContracts.coordinate(Kind.GOLDEN_MASTERS, "qits-qits-x", "", Ecosystem.NPM),
        "one prefix, not every prefix");
    assertEquals(
        "@qits/landing-golden-masters",
        CiContracts.coordinate(Kind.GOLDEN_MASTERS, "landing", "", Ecosystem.NPM),
        "an application without the prefix keeps its name");
  }

  @Test
  public void packagesComeGoldenMastersFirstThenEachProviderInDeclaredOrder() {
    CiContracts both =
        contracts(
            """
            contracts:
              application: qits-x
              golden-masters: { from: gm/, packages: [npm, maven] }
              pacts:
                qits-b-service: { packages: [maven] }
                qits-a-frontend: { packages: [npm, maven] }
            """);
    List<Package> packages = both.packages("qits-x-service");
    assertEquals(
        List.of(
            "@qits/x-golden-masters",
            "eu.wohlben.qits:qits-x-golden-masters",
            "eu.wohlben.qits:qits-x-service-pacts-qits-b-service",
            "@qits/x-service-pacts-qits-a-frontend",
            "eu.wohlben.qits:qits-x-service-pacts-qits-a-frontend"),
        packages.stream().map(Package::name).toList());
    assertEquals("qits-b-service", packages.get(2).provider());
    assertEquals("pacts/", packages.get(2).from(), "every pact package packs from the flat pacts/");
    assertEquals("", packages.get(0).provider());
    assertEquals(2, both.goldenMasterPackages().size());
    for (Package contract : packages) {
      assertEquals(CiArtifact.Publish.IF_CHANGED, contract.artifact().publish());
      assertEquals(contract.ecosystem().type(), contract.artifact().type());
    }
  }

  // --- parse errors ------------------------------------------------------------------------------

  @Test
  public void aSectionThatIsNotAMappingOrDeclaresNoTreeIsRefused() {
    assertTrue(refused("contracts: [maven]\n").getMessage().contains("must be a mapping"));
    assertTrue(
        refused("contracts:\n  application: qits-x\n")
            .getMessage()
            .contains("declares neither 'golden-masters' nor 'pacts'"));
  }

  @Test
  public void theApplicationIsRequiredAndASlug() {
    assertTrue(
        refused("contracts:\n  golden-masters: { from: gm/, packages: [maven] }\n")
            .getMessage()
            .contains("contracts.application is missing"));
    assertTrue(
        refused(
                "contracts:\n  application: Qits_X\n  golden-masters: { from: gm/, packages:"
                    + " [maven] }\n")
            .getMessage()
            .contains("'Qits_X'"));
  }

  @Test
  public void anUnknownKeyAtAnyLevelIsRefused() {
    assertTrue(
        refused(
                "contracts:\n  application: qits-x\n  goldens: { from: gm/, packages: [maven] }\n")
            .getMessage()
            .contains("unknown key 'goldens'"));
    assertTrue(
        refused(
                "contracts:\n  application: qits-x\n  golden-masters: { from: gm/, packages:"
                    + " [maven], dir: x }\n")
            .getMessage()
            .contains("unknown key 'dir'"));
    assertTrue(
        refused(
                "contracts:\n  application: qits-x\n  pacts:\n    qits-p-service: { packages:"
                    + " [maven], to: y }\n")
            .getMessage()
            .contains("unknown key 'to'"));
  }

  @Test
  public void packagesAreANonEmptyDistinctSubsetOfTheClosedList() {
    String cargo =
        refused(
                "contracts:\n  application: qits-x\n  pacts:\n    qits-projects-service:"
                    + " { packages: [maven, cargo] }\n")
            .getMessage();
    assertTrue(
        cargo.contains(
            "contracts.pacts.qits-projects-service.packages names 'cargo' — this qits-ci packages maven and"
                + " npm"),
        cargo);
    assertTrue(
        refused(
                "contracts:\n  application: qits-x\n  golden-masters: { from: gm/, packages: [] }\n")
            .getMessage()
            .contains("non-empty list"));
    assertTrue(
        refused(
                "contracts:\n  application: qits-x\n  golden-masters: { from: gm/, packages: [npm,"
                    + " npm] }\n")
            .getMessage()
            .contains("twice"));
  }

  @Test
  public void publishIsNotOfferedAnywhereInside() {
    for (String yaml :
        new String[] {
          "contracts:\n  application: qits-x\n  publish: always\n  golden-masters: { from: gm/,"
              + " packages: [maven] }\n",
          "contracts:\n  application: qits-x\n  golden-masters: { from: gm/, packages: [maven],"
              + " publish: if-changed }\n",
          "contracts:\n  application: qits-x\n  pacts:\n    publish: if-changed\n",
          "contracts:\n  application: qits-x\n  pacts:\n    qits-p-service: { packages: [maven],"
              + " publish: always }\n"
        }) {
      String message = refused(yaml).getMessage();
      assertTrue(message.contains("every contract package is if-changed by definition"), message);
    }
  }

  @Test
  public void pactsMustBeANonEmptyMappingKeyedByProvider() {
    assertTrue(
        refused("contracts:\n  application: qits-x\n  pacts: {}\n")
            .getMessage()
            .contains("non-empty mapping keyed by provider"));
    assertTrue(
        refused("contracts:\n  application: qits-x\n  pacts: [qits-projects]\n")
            .getMessage()
            .contains("non-empty mapping keyed by provider"));
    assertTrue(
        refused(
                "contracts:\n  application: qits-x\n  pacts:\n    Projects: { from: p/, packages:"
                    + " [maven] }\n")
            .getMessage()
            .contains("provider 'Projects'"));
  }

  @Test
  public void aPactProviderIsARepositoryNameAndFromIsGone() {
    String application =
        refused("contracts:\n  application: qits-x\n  pacts:\n    qits-projects: { packages: [maven] }\n")
            .getMessage();
    assertTrue(application.contains("provider 'qits-projects'"), application);
    assertTrue(application.contains("REPOSITORY name"), application);
    String from =
        refused(
                "contracts:\n  application: qits-x\n  pacts:\n    qits-projects-service: { from:"
                    + " pacts/, packages: [maven] }\n")
            .getMessage();
    assertTrue(from.contains("declares 'from'"), from);
    assertTrue(from.contains("<consumer repository>_<provider repository>.json"), from);
  }

  @Test
  public void fromIsARelativeDownwardPath() {
    assertTrue(
        refused(
                "contracts:\n  application: qits-x\n  golden-masters: { from: ../gm, packages:"
                    + " [maven] }\n")
            .getMessage()
            .contains("downwards"));
    assertTrue(
        refused("contracts:\n  application: qits-x\n  golden-masters: { packages: [maven] }\n")
            .getMessage()
            .contains("contracts.golden-masters.from is missing"));
  }
}
