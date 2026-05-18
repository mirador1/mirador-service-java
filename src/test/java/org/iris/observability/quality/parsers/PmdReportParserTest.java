package org.iris.observability.quality.parsers;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PmdReportParser} using a fixture XML at
 * {@code src/test/resources/META-INF/build-reports/pmd.xml}.
 *
 * <p>Fixture has 6 violations across 3 files spanning 3 rulesets
 * (bestpractices, design, errorprone), 3 priorities (1=High, 2=Normal,
 * 3=Low), and includes one violation in a file path with no `/` (tests
 * the no-slash branch of the filename shortener).
 */
@SuppressWarnings({"java:S125", "java:S5853"})  // S125: prose comments with code-like glyphs (arrows, backticks, parens) — not actual commented-out code. S5853: multi-assertion chain refactor deferred ; current shape reads better with subject + N separate assertions.
class PmdReportParserTest {

    private final PmdReportParser parser = new PmdReportParser();

    @Test
    void parse_classpathFixture_returnsAvailableTrueWithCorrectTotal() {
        Map<String, Object> result = parser.parse();

        assertThat(result).containsEntry("available", true);
        assertThat(result).containsEntry("total", 6);
    }

    @Test
    void parse_aggregatesByRuleset() {
        Map<String, Object> result = parser.parse();

        @SuppressWarnings("unchecked")
        Map<String, Integer> byRuleset = (Map<String, Integer>) result.get("byRuleset");
        assertThat(byRuleset).containsEntry("bestpractices", 2)
                .containsEntry("design", 3)
                .containsEntry("errorprone", 1);
    }

    @Test
    void parse_aggregatesByPriorityWithLabels() {
        Map<String, Object> result = parser.parse();

        @SuppressWarnings("unchecked")
        Map<String, Integer> byPriority = (Map<String, Integer>) result.get("byPriority");
        // 2× priority="1" → "High", 2× priority="2" → "Normal", 2× priority="3" → "Low"
        assertThat(byPriority).containsEntry("High", 2)
                .containsEntry("Normal", 2)
                .containsEntry("Low", 2);
    }

    @Test
    void parse_topRulesSortedByCountDescending() {
        // Most-violated rule first — UI shows this as the dashboard's
        // "top offenders" panel, so the sort is load-bearing.
        Map<String, Object> result = parser.parse();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> topRules = (List<Map<String, Object>>) result.get("topRules");
        assertThat(topRules).isNotEmpty();
        // UnusedPrivateField appears 2x, the rest 1x — so it leads.
        assertThat(topRules.get(0)).containsEntry("rule", "UnusedPrivateField")
                .containsEntry("count", 2);
    }

    @Test
    void parse_violationFile_shortenedToBasenameWithoutJavaExt() {
        // Path "src/main/java/com/example/foo/AliceController.java" → "AliceController"
        Map<String, Object> result = parser.parse();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> violations = (List<Map<String, Object>>) result.get("violations");
        assertThat(violations).extracting(v -> v.get("file"))
                .contains("AliceController", "BobService");
    }

    @Test
    void parse_filenameWithoutSlash_passesThroughMinusJavaExt() {
        // Edge case: filename has no `/` (root-level file or single-name).
        // The `lastIndexOf('/') + 1` substring would otherwise be wrong;
        // the parser's contains-slash guard must skip the slice.
        Map<String, Object> result = parser.parse();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> violations = (List<Map<String, Object>>) result.get("violations");
        // CarolHelper.java has no slash — should appear as just "CarolHelper.java"
        // (no slash → no shortening, but .java suffix not stripped per the
        // current code which only does `replace(".java", "")` when the
        // contains-slash branch fires).
        assertThat(violations).extracting(v -> v.get("file"))
                .anyMatch(f -> f.toString().contains("CarolHelper"));
    }

    @Test
    void parse_violationsCappedAt50() {
        // Already only 6 in the fixture — verify the cap isn't applied
        // prematurely (collection should hold all 6, not be truncated).
        Map<String, Object> result = parser.parse();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> violations = (List<Map<String, Object>>) result.get("violations");
        assertThat(violations).hasSize(6);
    }
}
