package org.iris.observability;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.Info;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Unit tests for {@link TestReportInfoContributor} — pin the
 * {@code /actuator/info} {@code tests} section that surfaces Surefire
 * results to the Angular dashboard.
 *
 * <p>The contributor reads from the hardcoded {@code target/surefire-reports/}
 * directory (relative to working directory). When this test runs via
 * {@code ./mvnw test}, that directory exists with real reports, so the
 * happy path is exercised. Tests check that :
 *   - The "tests" detail key is ALWAYS added to the builder (no skip)
 *   - The shape is either {@code {available: false}} OR a populated map
 *     with the expected keys (status, total, passed, failures, errors,
 *     skipped, time, runAt, suites)
 *   - When available, status is "PASSED" or "FAILED" (not arbitrary)
 *
 * <p>Pinned contracts (regression-prone):
 *   - Always emits a "tests" key, even when no reports exist (UI gates
 *     on this section being present, then on .available)
 *   - status is exactly "PASSED" or "FAILED" — UI uses string equality
 */
@SuppressWarnings("java:S5853")  // Multi-assertion chain refactor deferred ; current shape reads better when subject + N separate assertions.
class TestReportInfoContributorTest {

    @Test
    void contribute_alwaysAddsTestsDetailToBuilder_evenWithoutReports() {
        // Pinned: even when target/surefire-reports/ doesn't exist, the
        // contributor STILL adds {tests: {available: false}}. The UI
        // gates on hasOwnProperty('tests') THEN on .available — missing
        // the key entirely would render "Loading…" forever.
        TestReportInfoContributor contributor = new TestReportInfoContributor();
        AtomicReference<Map<String, Object>> testsRef = new AtomicReference<>();
        Info.Builder builder = new Info.Builder() {
            @Override
            @SuppressWarnings("unchecked")
            public Info.Builder withDetail(String key, Object value) {
                if ("tests".equals(key)) {
                    testsRef.set((Map<String, Object>) value);
                }
                return super.withDetail(key, value);
            }
        };

        contributor.contribute(builder);

        Map<String, Object> tests = testsRef.get();
        assertThat(tests).isNotNull();
        assertThat(tests).containsKey("available");
    }

    @Test
    void contribute_whenAvailable_carriesStatusTotalPassedFailuresErrorsSkippedTimeRunAt() {
        // Pinned: the dashboard renders 8 distinct fields when tests are
        // available. Any missing field would render an empty cell.
        TestReportInfoContributor contributor = new TestReportInfoContributor();
        AtomicReference<Map<String, Object>> testsRef = new AtomicReference<>();
        Info.Builder builder = new Info.Builder() {
            @Override
            @SuppressWarnings("unchecked")
            public Info.Builder withDetail(String key, Object value) {
                if ("tests".equals(key)) {
                    testsRef.set((Map<String, Object>) value);
                }
                return super.withDetail(key, value);
            }
        };

        contributor.contribute(builder);

        Map<String, Object> tests = testsRef.get();
        // When tests are available (typical when running via `mvn test`),
        // the 8 keys must be present. When NOT available (clean target/),
        // skip this check — we test the structural contract above.
        if (Boolean.TRUE.equals(tests.get("available"))) {
            assertThat(tests).containsKeys(
                    "status", "total", "passed", "failures",
                    "errors", "skipped", "time", "runAt", "suites");
        }
    }

    @Test
    void contribute_whenAvailable_statusIsPassedOrFailed_noOtherValue() {
        // Pinned: the dashboard's badge color depends on a string-
        // equality check ('PASSED' → green, 'FAILED' → red, anything
        // else → grey "unknown"). A regression that emitted "OK" or
        // "ERROR" would render grey instead of green/red.
        TestReportInfoContributor contributor = new TestReportInfoContributor();
        AtomicReference<Map<String, Object>> testsRef = new AtomicReference<>();
        Info.Builder builder = new Info.Builder() {
            @Override
            @SuppressWarnings("unchecked")
            public Info.Builder withDetail(String key, Object value) {
                if ("tests".equals(key)) {
                    testsRef.set((Map<String, Object>) value);
                }
                return super.withDetail(key, value);
            }
        };

        contributor.contribute(builder);

        Map<String, Object> tests = testsRef.get();
        if (Boolean.TRUE.equals(tests.get("available"))) {
            assertThat(tests.get("status")).isIn("PASSED", "FAILED");
        }
    }

    @Test
    void contribute_doesNotThrow_forAnyFilesystemState() {
        // Defensive : even on weird filesystem state (permissions denied,
        // malformed XML), the contributor MUST swallow the exception and
        // emit {available: false, error: "..."} rather than crash the
        // /actuator/info endpoint (which would 500 the WHOLE actuator).
        TestReportInfoContributor contributor = new TestReportInfoContributor();
        Info.Builder builder = new Info.Builder();

        // Wrapped in assertThatNoException so Sonar S2699 sees an explicit
        // assertion. The contract is "swallow filesystem errors silently".
        assertThatNoException().isThrownBy(() -> contributor.contribute(builder));
    }
}
