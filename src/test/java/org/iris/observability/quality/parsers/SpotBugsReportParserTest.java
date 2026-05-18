package org.iris.observability.quality.parsers;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SpotBugsReportParser} using a fixture XML on the
 * test classpath at
 * {@code META-INF/build-reports/spotbugsXml.xml}. The parser's
 * classpath-first lookup picks it up automatically — no mocks needed.
 *
 * <p>Fixture has 4 BugInstances spanning 2 categories (MALICIOUS_CODE,
 * STYLE), 3 priorities (1=High, 2=Normal, 3=Low), and one entry without
 * a package-qualified class name (tests the fallback branch).
 */
@SuppressWarnings({"java:S125", "java:S5853"})  // S125: prose comments with code-like glyphs (arrows, backticks, parens) — not actual commented-out code. S5853: multi-assertion chain refactor deferred ; current shape reads better with subject + N separate assertions.
class SpotBugsReportParserTest {

    private final SpotBugsReportParser parser = new SpotBugsReportParser();

    @Test
    void parse_classpathFixture_returnsAvailableTrueWithCorrectTotal() {
        Map<String, Object> result = parser.parse();

        assertThat(result).containsEntry("available", true);
        assertThat(result).containsEntry("total", 4);
    }

    @Test
    void parse_aggregatesByCategory() {
        Map<String, Object> result = parser.parse();

        @SuppressWarnings("unchecked")
        Map<String, Integer> byCategory = (Map<String, Integer>) result.get("byCategory");
        assertThat(byCategory).containsEntry("MALICIOUS_CODE", 2)
                .containsEntry("STYLE", 2);
    }

    @Test
    void parse_aggregatesByPriorityWithLabels() {
        // Priority numbers map to human labels:
        //  1 → High, 2 → Normal, 3 → Low (any other value passes through verbatim).
        Map<String, Object> result = parser.parse();

        @SuppressWarnings("unchecked")
        Map<String, Integer> byPriority = (Map<String, Integer>) result.get("byPriority");
        assertThat(byPriority).containsEntry("High", 2)   // 2 priority="1" entries
                .containsEntry("Normal", 1)                // 1 priority="2" entry
                .containsEntry("Low", 1);                  // 1 priority="3" entry
    }

    @Test
    void parse_extractsShortClassNameFromFirstNestedClassElement() {
        Map<String, Object> result = parser.parse();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        // Package-qualified className gets shortened to the simple name
        assertThat(items.get(0)).containsEntry("className", "AliceController");
        assertThat(items.get(1)).containsEntry("className", "BobService");
        assertThat(items.get(2)).containsEntry("className", "CarolHelper");
    }

    @Test
    void parse_classNameWithoutDots_passesThroughUnchanged() {
        // Edge case: the 4th BugInstance uses "DaveDirect" with no package
        // prefix. The lastIndexOf('.') branch must NOT fire when there's
        // no dot — pinned because the substring(lastIndexOf+1) would
        // otherwise return "" and we'd lose the class name.
        Map<String, Object> result = parser.parse();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertThat(items.get(3)).containsEntry("className", "DaveDirect");
    }

    @Test
    void parse_eachItemHasFourRequiredFields() {
        // Schema check — UI table reads { category, priority, type, className }
        // per row; missing field would render an empty cell.
        Map<String, Object> result = parser.parse();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertThat(items).hasSize(4);
        for (Map<String, Object> item : items) {
            assertThat(item).containsKeys("category", "priority", "type", "className");
        }
    }
}
