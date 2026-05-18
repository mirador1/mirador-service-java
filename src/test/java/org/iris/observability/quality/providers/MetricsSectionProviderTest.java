package org.iris.observability.quality.providers;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MetricsSectionProvider} using the same JaCoCo
 * CSV fixture at {@code src/test/resources/META-INF/build-reports/jacoco.csv}
 * as {@link JacocoReportParserTest}. This provider extracts a different
 * shape (per-package complexity + top-10 + untested classes) so the
 * tests pin distinct invariants.
 */
@SuppressWarnings("java:S5853")  // Multi-assertion chain refactor deferred ; current shape reads better when subject + N separate assertions.
class MetricsSectionProviderTest {

    private final MetricsSectionProvider provider = new MetricsSectionProvider();

    @Test
    void parse_classpathFixture_returnsAvailableTrueWithCounters() {
        Map<String, Object> result = provider.parse();

        assertThat(result).containsEntry("available", true);
        // Fixture has 4 valid rows (5th is malformed → skipped).
        assertThat(result).containsEntry("totalClasses", 4L);
    }

    @Test
    void parse_aggregatesTotalsAcrossAllRows() {
        Map<String, Object> result = provider.parse();

        // Methods: 1+9 + 2+8 + 5+0 + 1+9 = 35
        assertThat(result).containsEntry("totalMethods", 35L);
        // Lines: 1+9 + 5+5 + 10+0 + 2+8 = 40
        assertThat(result).containsEntry("totalLines", 40L);
        // Complexity: 2+8 + 5+5 + 10+0 + 1+9 = 40
        assertThat(result).containsEntry("totalComplexity", 40L);
    }

    @Test
    void parse_packagesAggregatedByShortName() {
        // Pinned: packages keyed by short name (last segment) so foo +
        // bar + (default) appear distinctly. Same convention as
        // JacocoReportParser for the dashboard.
        Map<String, Object> result = provider.parse();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> packages = (List<Map<String, Object>>) result.get("packages");
        assertThat(packages).extracting(p -> p.get("name"))
                .contains("foo", "bar");
    }

    @Test
    void parse_topComplexClassesSortedDescByComplexity() {
        // Pinned: top-10 by complexity DESC. UI ranks them from worst
        // to best — flipping sort would silently invert the panel.
        Map<String, Object> result = provider.parse();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> top = (List<Map<String, Object>>) result.get("topComplexClasses");
        assertThat(top).isNotEmpty();
        // Each entry has class + complexity
        for (Map<String, Object> entry : top) {
            assertThat(entry).containsKeys("class", "complexity");
        }
        // Verify desc order
        for (int i = 1; i < top.size(); i++) {
            long prev = (Long) top.get(i - 1).get("complexity");
            long curr = (Long) top.get(i).get("complexity");
            assertThat(curr).isLessThanOrEqualTo(prev);
        }
    }

    @Test
    void parse_untestedClassesIncludesZeroCoverageNonZeroMethods() {
        // Pinned: a class is "untested" iff method_covered == 0 AND
        // method_total > 0 (excludes pure data classes / records that
        // have 0 methods — no logic to test).
        Map<String, Object> result = provider.parse();

        @SuppressWarnings("unchecked")
        List<String> untested = (List<String>) result.get("untestedClasses");
        // CarolHelper has 5 methods missed + 0 covered → counts as untested.
        assertThat(untested).contains("CarolHelper");
    }

    @Test
    void parse_untestedClassesAlphabeticallySorted() {
        Map<String, Object> result = provider.parse();

        @SuppressWarnings("unchecked")
        List<String> untested = (List<String>) result.get("untestedClasses");
        for (int i = 1; i < untested.size(); i++) {
            assertThat(untested.get(i).compareTo(untested.get(i - 1))).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void parse_untestedCountMatchesUntestedClassesSize() {
        // Sanity: count and list must agree (the count exists separately
        // for UI display in a "X untested classes" badge).
        Map<String, Object> result = provider.parse();

        @SuppressWarnings("unchecked")
        List<String> untested = (List<String>) result.get("untestedClasses");
        assertThat(result).containsEntry("untestedCount", untested.size());
    }
}
