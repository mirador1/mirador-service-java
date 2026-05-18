package org.iris.observability.quality.parsers;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link JacocoReportParser} using a CSV fixture at
 * {@code src/test/resources/META-INF/build-reports/jacoco.csv}.
 *
 * <p>Fixture has 4 valid class rows + 1 malformed line (only 2 columns).
 * Coverage shape: AliceController (90%), BobService (50%), CarolHelper
 * (0%), RootClass (80%) — totals + per-package aggregation pinned.
 */
@SuppressWarnings("java:S5853")  // Multi-assertion chain refactor deferred ; current shape reads better when subject + N separate assertions.
class JacocoReportParserTest {

    private final JacocoReportParser parser = new JacocoReportParser();

    @Test
    void parse_classpathFixture_returnsAvailableTrue() {
        Map<String, Object> result = parser.parse();

        assertThat(result).containsEntry("available", true);
    }

    @Test
    void parse_aggregatesInstructionTotalsAcrossAllRows() {
        Map<String, Object> result = parser.parse();

        @SuppressWarnings("unchecked")
        Map<String, Object> instr = (Map<String, Object>) result.get("instructions");
        // Sum across 4 rows: missed = 10+50+100+20 = 180; covered = 90+50+0+80 = 220; total = 400
        assertThat(instr).containsEntry("covered", 220L)
                .containsEntry("total", 400L);
        // Pct = 220/400 = 55.0
        assertThat(instr).containsEntry("pct", 55.0);
    }

    @Test
    void parse_aggregatesBranchAndLineCounters() {
        Map<String, Object> result = parser.parse();

        @SuppressWarnings("unchecked")
        Map<String, Object> branches = (Map<String, Object>) result.get("branches");
        @SuppressWarnings("unchecked")
        Map<String, Object> lines = (Map<String, Object>) result.get("lines");

        // Branches: missed = 2+5+10+2 = 19; covered = 8+5+0+8 = 21; total = 40
        assertThat(branches).containsEntry("covered", 21L)
                .containsEntry("total", 40L);
        // Lines: missed = 1+5+10+2 = 18; covered = 9+5+0+8 = 22; total = 40
        assertThat(lines).containsEntry("covered", 22L)
                .containsEntry("total", 40L);
    }

    @Test
    void parse_aggregatesMethodCounters() {
        Map<String, Object> result = parser.parse();

        @SuppressWarnings("unchecked")
        Map<String, Object> methods = (Map<String, Object>) result.get("methods");
        // Methods: missed = 1+2+5+1 = 9; covered = 9+8+0+9 = 26; total = 35
        assertThat(methods).containsEntry("covered", 26L)
                .containsEntry("total", 35L);
    }

    @Test
    void parse_perPackageAggregation_includesAllPackagesAndDefault() {
        // Package paths come in as `com/example/foo` (slash form); the parser
        // converts to dotted form and then takes the LAST segment as the
        // display name. Empty package becomes "(default)".
        Map<String, Object> result = parser.parse();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> packages = (List<Map<String, Object>>) result.get("packages");
        assertThat(packages).extracting(p -> p.get("name"))
                .contains("foo", "bar", "(default)");
    }

    @Test
    void parse_skipsMalformedLines() {
        // The fixture has one row with only 2 columns ("malformed-line-not-13-cols,no").
        // Parser must skip silently — total stays at 4 valid rows.
        Map<String, Object> result = parser.parse();

        @SuppressWarnings("unchecked")
        Map<String, Object> instr = (Map<String, Object>) result.get("instructions");
        // If the malformed line had been counted (NumberFormatException at
        // Long.parseLong), the total would be wrong or the parse would fail.
        assertThat(instr).containsEntry("total", 400L); // exactly 4 valid rows
    }

    @Test
    void parse_packagePctMatchesExpectedFormula() {
        // foo package: instr = 60 covered / 200 total = 30%
        // (Alice 90/100 + Bob 50/100). round1 keeps 1 decimal.
        Map<String, Object> result = parser.parse();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> packages = (List<Map<String, Object>>) result.get("packages");
        Map<String, Object> foo = packages.stream()
                .filter(p -> "foo".equals(p.get("name")))
                .findFirst().orElseThrow();
        // 140/200 = 70.0%
        assertThat((Double) foo.get("instructionPct")).isEqualTo(70.0);
    }
}
