package org.iris.observability.quality.parsers;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CheckstyleReportParser} using a fixture XML at
 * {@code src/test/resources/META-INF/build-reports/checkstyle-result.xml}.
 *
 * <p>Fixture has 4 errors across 2 files, 3 severities (error, warning,
 * info), and 3 different checker classes (MissingJavadocMethodCheck,
 * MagicNumberCheck, CyclomaticComplexityCheck).
 */
@SuppressWarnings("java:S5853")  // Multi-assertion chain refactor deferred ; current shape reads better when subject + N separate assertions.
class CheckstyleReportParserTest {

    private final CheckstyleReportParser parser = new CheckstyleReportParser();

    @Test
    void parse_classpathFixture_returnsAvailableTrueWithCorrectTotal() {
        Map<String, Object> result = parser.parse();

        assertThat(result).containsEntry("available", true);
        assertThat(result).containsEntry("total", 4);
    }

    @Test
    void parse_aggregatesBySeverity() {
        Map<String, Object> result = parser.parse();

        @SuppressWarnings("unchecked")
        Map<String, Integer> bySeverity = (Map<String, Integer>) result.get("bySeverity");
        assertThat(bySeverity).containsEntry("error", 1)
                .containsEntry("warning", 2)
                .containsEntry("info", 1);
    }

    @Test
    void parse_topCheckersSortedByCountDescending() {
        // MissingJavadocMethodCheck appears 2x, the others 1x each — leads.
        Map<String, Object> result = parser.parse();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> topCheckers = (List<Map<String, Object>>) result.get("topCheckers");
        assertThat(topCheckers).isNotEmpty();
        assertThat(topCheckers.get(0)).containsEntry("checker", "MissingJavadocMethodCheck")
                .containsEntry("count", 2);
    }

    @Test
    void parse_checkerNameShortenedFromFqcn() {
        // source="com.puppycrawl.tools.checkstyle.checks.coding.MagicNumberCheck"
        // → "MagicNumberCheck" (everything after the last `.`)
        Map<String, Object> result = parser.parse();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> violations = (List<Map<String, Object>>) result.get("violations");
        assertThat(violations).extracting(v -> v.get("checker"))
                .contains("MissingJavadocMethodCheck", "MagicNumberCheck",
                        "CyclomaticComplexityCheck");
    }

    @Test
    void parse_eachViolationHasFiveRequiredFields() {
        Map<String, Object> result = parser.parse();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> violations = (List<Map<String, Object>>) result.get("violations");
        for (Map<String, Object> v : violations) {
            assertThat(v).containsKeys("file", "line", "severity", "checker", "message");
        }
    }

    @Test
    void parse_violationFile_shortenedToBasename() {
        Map<String, Object> result = parser.parse();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> violations = (List<Map<String, Object>>) result.get("violations");
        assertThat(violations).extracting(v -> v.get("file"))
                .contains("AliceController", "BobService");
    }
}
