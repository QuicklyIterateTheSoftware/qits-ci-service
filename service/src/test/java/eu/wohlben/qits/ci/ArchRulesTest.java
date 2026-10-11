package eu.wohlben.qits.ci;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;
import eu.wohlben.qits.archrules.HttpClientRules;

/**
 * The platform's shared ArchUnit rules over THIS module's classes — {@code service}'s own copy,
 * because {@code ci}'s {@code ArchRulesTest} analyzes {@code eu.wohlben.qits.ci} too, but on {@code
 * ci}'s own test classpath, which cannot reach a module that depends on it. Every hand-rolled {@code
 * java.net.http.HttpClient} in this repository is built here — {@code githost/}, {@code projects/},
 * {@code registry/}, {@code idp/} — so this is the copy that actually judges something.
 *
 * <p>{@link HttpClientRules} is the completeness guard for traced service-to-service HTTP: a class
 * that calls {@code HttpClient.newBuilder()}/{@code newHttpClient()} must also use {@code
 * QitsHttpClients} (qits-http-core) or declare {@code @RawHttpClient(reason = "...")}. Every client
 * here is internal to the platform and is wrapped; none is annotated raw.
 */
@AnalyzeClasses(
    packages = "eu.wohlben.qits.ci",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchRulesTest {

  @ArchTest static final ArchTests HTTP_CLIENTS = ArchTests.in(HttpClientRules.class);
}
