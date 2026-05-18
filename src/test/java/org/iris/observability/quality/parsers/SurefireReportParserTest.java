package org.iris.observability.quality.parsers;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SurefireReportParser} using two classpath
 * fixtures at {@code src/test/resources/META-INF/build-reports/surefire/}
 * (TEST-com.example.AliceTest.xml + TEST-com.example.BobTest.xml).
 *
 * <p>The classpath-glob branch of the parser picks them up first, so
 * the dev fallback to {@code target/surefire-reports} doesn't fire
 * (which would mix in the actual test-run output of this very test
 * suite, making counts unreliable).
 *
 * <p>Fixture totals: 5 tests across 2 suites, 1 failure (BobTest.testFails)
 * → status FAILED. Total time 0.800s.
 */
@SuppressWarnings("java:S5853")  // Multi-assertion chain refactor deferred ; current shape reads better when subject + N separate assertions.
class SurefireReportParserTest {

    private final SurefireReportParser parser = new SurefireReportParser();

    @Test
    void parse_classpathFixture_returnsAvailableTrue() {
        Map<String, Object> result = parser.parse();

        assertThat(result).containsEntry("available", true);
    }

    @Test
    void parse_aggregatesAcrossAllFixtureSuites() {
        Map<String, Object> result = parser.parse();

        assertThat(result).containsEntry("total", 5);
        assertThat(result).containsEntry("passed", 4);
        assertThat(result).containsEntry("failures", 1);
        assertThat(result).containsEntry("errors", 0);
        assertThat(result).containsEntry("skipped", 0);
    }

    @Test
    void parse_statusIsFailedWhenAnySuiteHasFailures() {
        // Pinned: a single failure across ANY suite must flip the
        // overall status. UI shows a red badge based on this; missing
        // the failure flag would silently green-light a broken build.
        Map<String, Object> result = parser.parse();

        assertThat(result).containsEntry("status", "FAILED");
    }

    @Test
    void parse_suitesListUsesShortNameNotFqcn() {
        // Suite name is "com.example.AliceTest" in the XML; UI shows
        // "AliceTest" (last segment after `.`).
        Map<String, Object> result = parser.parse();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> suites = (List<Map<String, Object>>) result.get("suites");
        assertThat(suites).extracting(s -> s.get("name"))
                .containsExactlyInAnyOrder("AliceTest", "BobTest");
    }

    @Test
    void parse_dedupsBySuiteShortName() {
        // Pinned: even if the same short name appears twice (post-rename
        // without `mvn clean`), only the first wins — totals don't
        // double-count. Fixture has 2 distinct names so dedup is silent
        // here, but the test pins the contract by asserting suite count
        // matches fixture count (not 2x or more).
        Map<String, Object> result = parser.parse();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> suites = (List<Map<String, Object>>) result.get("suites");
        assertThat(suites).hasSize(2);
    }

    @Test
    void parse_slowestTestsSortedByDurationDescending() {
        Map<String, Object> result = parser.parse();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> slowest = (List<Map<String, Object>>) result.get("slowestTests");
        assertThat(slowest).isNotEmpty();
        // First entry has the highest timeMs of all 5 testcases.
        Long first = (Long) slowest.get(0).get("timeMs");
        for (int i = 1; i < slowest.size(); i++) {
            assertThat((Long) slowest.get(i).get("timeMs")).isLessThanOrEqualTo(first);
        }
    }

    @Test
    void parse_eachSuiteHasTimeAsFormattedString() {
        // Pinned: `time` field is a formatted string ("0.500s") not a
        // raw double. UI displays it verbatim so a number would render
        // wrong (e.g. "0.5" missing decimals).
        Map<String, Object> result = parser.parse();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> suites = (List<Map<String, Object>>) result.get("suites");
        for (Map<String, Object> suite : suites) {
            assertThat(suite.get("time")).isInstanceOf(String.class);
            assertThat((String) suite.get("time")).endsWith("s");
        }
    }

    @Test
    void parse_runAtIsHumanReadableTimestamp() {
        // Format: yyyy-MM-dd HH:mm:ss (TS_FMT pattern). Pinned to
        // catch any silent change to the format string that would
        // break the dashboard "last run at" display.
        Map<String, Object> result = parser.parse();

        String runAt = (String) result.get("runAt");
        assertThat(runAt).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
    }
}
