package eu.wohlben.qits.ci;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;
import eu.wohlben.qits.archrules.CausationRowRules;
import eu.wohlben.qits.archrules.HttpClientRules;

/**
 * The platform's shared ArchUnit rules over this repository's classes. Today that is the
 * causation-row completeness guard: every {@code @Entity} either implements {@code CausedRow} or
 * declares {@code @Uncaused}, so a new entity that skips the decision fails this build naming the
 * class instead of leaving a silent hole in the trace. The rule set lives in qits-arch-rules
 * (qits-integrations-quarkus); a new set added there arrives here as one more {@code @ArchTest}
 * line.
 *
 * <p>{@link HttpClientRules} is the second such set, and it holds here over an EMPTY result: this
 * module ({@code qits-ci-domain}, {@code ci/}) is framework-free and builds no {@code
 * java.net.http.HttpClient} at all — every hand-rolled client lives in {@code service/}, under the
 * same {@code eu.wohlben.qits.ci} package root but a module this one's test classpath cannot reach
 * ({@code service} depends on {@code ci}, never the reverse). {@code service} carries its own copy
 * of this test over its own classes. {@code HttpClientRules.rawHttpClientsAreWrappedOrDeclared}
 * ships {@code allowEmptyShould(true)} for exactly this shape: a module with no client at all is
 * the rule's best outcome, not a misconfigured one.
 */
@AnalyzeClasses(
    packages = "eu.wohlben.qits.ci",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchRulesTest {

  @ArchTest static final ArchTests CAUSATION = ArchTests.in(CausationRowRules.class);
  @ArchTest static final ArchTests HTTP_CLIENTS = ArchTests.in(HttpClientRules.class);
}
