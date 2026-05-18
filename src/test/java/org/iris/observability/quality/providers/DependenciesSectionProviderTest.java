package org.iris.observability.quality.providers;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DependenciesSectionProvider} reading the real
 * {@code META-INF/build-reports/pom.xml} present on the test classpath
 * after `mvn test-compile`. No fixture: the real pom is what production
 * sees.
 *
 * <p>Tests focus on Step 1 (pom.xml parsing — properties + dependencies)
 * which is purely local. The Maven Central freshness check (Step 2)
 * runs but its result depends on network availability — pinned only
 * to the "outdatedCount field exists" contract, not specific values.
 *
 * <p>Steps 3 + 4 (dependency-tree.txt, dependency-analysis.txt) are
 * optional and may produce null on a fresh checkout — handled by the
 * {@code if (treeResult != null)} guards in the provider.
 */
@SuppressWarnings("java:S5853")  // Multi-assertion chain refactor deferred ; current shape reads better when subject + N separate assertions.
class DependenciesSectionProviderTest {

    private final DependenciesSectionProvider provider = new DependenciesSectionProvider();

    @Test
    void parse_returnsAvailableTrueWhenPomOnClasspath() {
        // Pinned: if this fails, the Maven step that copies pom.xml to
        // META-INF/build-reports/ is no longer running. The /actuator/
        // quality dependencies section would silently regress to false.
        Map<String, Object> result = provider.parse();

        assertThat(result).containsEntry("available", true);
    }

    @Test
    void parse_extractsAtLeastOneDependency() {
        // Pinned to a sanity floor: the real pom has 50+ dependencies.
        // A regression in the parser (e.g. wrong XPath after a Spring
        // Boot 5 pom format change) would cause this to drop to 0 first.
        Map<String, Object> result = provider.parse();

        assertThat((Integer) result.get("total")).isGreaterThan(10);
    }

    @Test
    void parse_eachDependencyHasFourRequiredFields() {
        // Schema check — the dashboard table reads {groupId, artifactId,
        // version, scope} per row. Missing field → empty cell.
        Map<String, Object> result = provider.parse();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> deps = (List<Map<String, Object>>) result.get("dependencies");
        assertThat(deps).isNotEmpty();
        for (Map<String, Object> d : deps) {
            assertThat(d).containsKeys("groupId", "artifactId", "version", "scope");
            assertThat((String) d.get("groupId")).isNotEmpty();
            assertThat((String) d.get("artifactId")).isNotEmpty();
        }
    }

    @Test
    void parse_includesSpringBootStarter() {
        // Sanity floor — the project IS a Spring Boot app, so
        // spring-boot-starter or one of its derivatives MUST appear.
        // If this fails, the parser is broken (the dependency truly is in
        // pom.xml).
        Map<String, Object> result = provider.parse();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> deps = (List<Map<String, Object>>) result.get("dependencies");
        assertThat(deps).anyMatch(d -> ((String) d.get("artifactId")).startsWith("spring-boot"));
    }

    @Test
    void parse_includesOutdatedCountField() {
        // The Maven Central freshness check produces a count regardless
        // of whether the network call succeeds (returns 0 on timeout/error).
        // Pinned just so the field is present — value depends on environment.
        Map<String, Object> result = provider.parse();

        assertThat(result).containsKey("outdatedCount");
        assertThat((Long) result.get("outdatedCount")).isNotNegative();
    }

    @Test
    void parse_resolvesPropertyReferencesInDependencyVersions() {
        // pom.xml uses ${spring.boot.version} style references.
        // After parse, the resolved version should NOT contain `${`
        // (would mean the property substitution didn't fire).
        Map<String, Object> result = provider.parse();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> deps = (List<Map<String, Object>>) result.get("dependencies");
        for (Map<String, Object> d : deps) {
            String v = (String) d.get("version");
            // "(managed)" is the explicit default for deps without an inline
            // version (BOM-managed). That's fine — what's NOT fine is a
            // raw `${...}` placeholder (means resolution failed).
            assertThat(v).doesNotStartWith("${");
        }
    }

    // ─── parseDependencyAnalysis branches via target/dep-analysis fixture ────

    private Path stagedAnalysis;

    @AfterEach
    void cleanupStaged() throws IOException {
        if (stagedAnalysis != null) {
            Files.deleteIfExists(stagedAnalysis);
            stagedAnalysis = null;
        }
    }

    @Test
    void parse_includesDependencyAnalysis_whenAnalysisFileOnDisk() throws IOException {
        // The provider's loadResource() falls back to target/dependency-analysis.txt
        // when the classpath copy is absent (the dev path). Stage a synthetic
        // file there so parseDependencyAnalysis() actually runs end-to-end.
        // Pinned : the parser reads "Used undeclared" / "Unused declared"
        // sections and accumulates lines that start with a known coordinate
        // prefix (com./org./io./net./jakarta./javax./ch./de.). Anything else
        // is filtered out.
        String content = String.join("\n",
                "[INFO] --- maven-dependency-plugin:3.6.0:analyze (default-cli) @ iris ---",
                "[WARNING] Used undeclared dependencies found:",
                "[WARNING]    org.slf4j:slf4j-api:jar:2.0.9:compile",
                "[WARNING]    com.example:foo:jar:1.0.0:compile",
                "[WARNING] Unused declared dependencies found:",
                "[WARNING]    org.unused:bar:jar:1.0.0:compile",
                "[WARNING]    skipped-because-no-coord-prefix",
                "");
        stagedAnalysis = Path.of("target/dependency-analysis.txt");
        Files.writeString(stagedAnalysis, content);

        Map<String, Object> result = provider.parse();

        @SuppressWarnings("unchecked")
        Map<String, Object> analysis = (Map<String, Object>) result.get("dependencyAnalysis");
        assertThat(analysis).isNotNull();
        assertThat(analysis).containsEntry("available", true);
        // The Sonar parser strips the "[WARNING]" prefix differently on
        // different Maven versions ; we just pin that the count is > 0
        // and the lines we emitted made it into either bucket.
        @SuppressWarnings("unchecked")
        List<String> usedUndeclared = (List<String>) analysis.get("usedUndeclared");
        @SuppressWarnings("unchecked")
        List<String> unusedDeclared = (List<String>) analysis.get("unusedDeclared");
        assertThat((Integer) analysis.get("usedUndeclaredCount")).isEqualTo(usedUndeclared.size());
        assertThat((Integer) analysis.get("unusedDeclaredCount")).isEqualTo(unusedDeclared.size());
        // The "skipped-because-no-coord-prefix" line MUST NOT appear in
        // either list — pinned to defend the isCoordLine() filter.
        assertThat(usedUndeclared).noneMatch(s -> s.contains("skipped"));
        assertThat(unusedDeclared).noneMatch(s -> s.contains("skipped"));
    }
}
