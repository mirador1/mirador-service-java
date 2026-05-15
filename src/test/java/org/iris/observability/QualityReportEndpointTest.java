package org.iris.observability;

import org.iris.observability.quality.providers.ApiSectionProvider;
import org.iris.observability.quality.providers.BuildInfoSectionProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link QualityReportEndpoint} — the {@code /actuator/quality}
 * actuator endpoint that aggregates 15+ quality sections for the Angular
 * dashboard.
 *
 * <p>Pinned contracts (the report() output shape — UI dashboard depends on it):
 *   - generatedAt timestamp present + ISO format
 *   - 10 build-time section keys ALWAYS present (tests/coverage/bugs/pmd/
 *     checkstyle/owasp/pitest/dependencies/licenses/metrics) — fall back to
 *     {available: false} when the classpath JSON is absent
 *   - 5 runtime section keys present (build/git/api/runtime/branches)
 *   - runtime section carries activeProfiles + uptime + JVM start time
 *   - delegated sections (build/api) come from the injected providers
 *
 * <p>NOT covered here (process exec, file system) :
 *   - git section (ProcessBuilder on git binary)
 *   - branches section (same)
 *   - JAR layers section (BOOT-INF/layers.idx classpath read)
 */
// eslint-disable-next-line max-lines-per-function
@SuppressWarnings({"java:S125", "java:S5853"})  // S125: prose comments with code-like glyphs (arrows, backticks, parens) — not actual commented-out code. S5853: multi-assertion chain refactor deferred ; current shape reads better with subject + N separate assertions.
class QualityReportEndpointTest {

    private RequestMappingHandlerMapping handlerMapping;
    private Environment environment;
    private StartupTimeTracker startupTimeTracker;
    private BuildInfoSectionProvider buildInfoSectionProvider;
    private ApiSectionProvider apiSectionProvider;
    private QualityReportEndpoint endpoint;

    @BeforeEach
    void setUp() {
        handlerMapping = mock(RequestMappingHandlerMapping.class);
        environment = mock(Environment.class);
        startupTimeTracker = mock(StartupTimeTracker.class);
        buildInfoSectionProvider = mock(BuildInfoSectionProvider.class);
        apiSectionProvider = mock(ApiSectionProvider.class);

        // Default provider stubs (return minimal "available" payloads so report()
        // doesn't crash on unexpected nulls; per-test stubs override these).
        when(buildInfoSectionProvider.parse()).thenReturn(Map.of("available", true));
        when(apiSectionProvider.parse()).thenReturn(Map.of("available", true, "total", 0));
        when(environment.getActiveProfiles()).thenReturn(new String[]{"test"});
        when(startupTimeTracker.getStartupDurationMs()).thenReturn(0L);

        endpoint = new QualityReportEndpoint(
                handlerMapping, environment, startupTimeTracker,
                buildInfoSectionProvider, apiSectionProvider);
    }

    @Test
    void report_includesGeneratedAtTimestamp_isoFormat() {
        // Pinned: the dashboard renders generatedAt as "Last updated:". A
        // missing field would show "Last updated: undefined". The format
        // is yyyy-MM-dd HH:mm:ss (per TS_FMT) — frontend depends on it
        // for parsing.
        Map<String, Object> result = endpoint.report();

        assertThat(result).containsKey("generatedAt");
        String ts = (String) result.get("generatedAt");
        assertThat(ts).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
    }

    @Test
    void report_includesAll10BuildTimeSectionKeys_fallbackToUnavailableWhenJsonMissing() {
        // Pinned: the 10 build-time sections (tests, coverage, bugs, pmd,
        // checkstyle, owasp, pitest, dependencies, licenses, metrics) come
        // from META-INF/quality-build-report.json generated at mvn
        // prepare-package. When the JSON is absent (running from IDE
        // without a Maven build), each section gets {available: false,
        // reason: "..."} — so the UI shows "Run mvn prepare-package"
        // instead of crashing.
        Map<String, Object> result = endpoint.report();

        // Tests run from `./mvnw test` typically have NO build-time JSON
        // because we're at test phase, not prepare-package phase. So the
        // fallback path is the one exercised here.
        for (String key : new String[]{
                "tests", "coverage", "bugs", "pmd", "checkstyle",
                "owasp", "pitest", "dependencies", "licenses", "metrics"
        }) {
            assertThat(result).containsKey(key);
            // Each is a Map with at least "available" key (true OR false)
            @SuppressWarnings("unchecked")
            Map<String, Object> section = (Map<String, Object>) result.get(key);
            assertThat(section).containsKey("available");
        }
    }

    @Test
    void report_includesAll5RuntimeSectionKeys() {
        // Pinned: 5 runtime sections always present : build, git, api,
        // runtime, branches. UI dashboard renders 5 distinct cards;
        // missing key → blank card.
        Map<String, Object> result = endpoint.report();

        assertThat(result).containsKeys("build", "git", "api", "runtime", "branches");
    }

    @Test
    void report_buildSection_comesFromBuildInfoSectionProvider() {
        // Pinned: the `build` section is the BuildInfoSectionProvider
        // output verbatim. A regression that cached or wrapped it would
        // break the contract surface.
        Map<String, Object> custom = Map.of(
                "available", true,
                "version", "1.2.3",
                "artifact", "test-app");
        when(buildInfoSectionProvider.parse()).thenReturn(custom);

        Map<String, Object> result = endpoint.report();

        assertThat(result).containsEntry("build", custom);
    }

    @Test
    void report_apiSection_comesFromApiSectionProvider() {
        // Pinned: `api` section comes from ApiSectionProvider. Same
        // contract verification as build.
        Map<String, Object> custom = Map.of(
                "available", true,
                "total", 42,
                "endpoints", java.util.List.of());
        when(apiSectionProvider.parse()).thenReturn(custom);

        Map<String, Object> result = endpoint.report();

        assertThat(result).containsEntry("api", custom);
    }

    @Test
    void report_runtimeSection_includesActiveProfiles() {
        // Pinned: the dashboard's "Active Spring profiles" badge reads
        // from this. A regression that hardcoded "default" would
        // misrepresent any prod / docker profile combination.
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod", "docker"});

        Map<String, Object> result = endpoint.report();

        @SuppressWarnings("unchecked")
        Map<String, Object> runtime = (Map<String, Object>) result.get("runtime");
        assertThat(runtime).containsKey("activeProfiles");
        assertThat((String[]) runtime.get("activeProfiles"))
                .containsExactly("prod", "docker");
    }

    @Test
    void report_runtimeSection_emptyActiveProfiles_fallsBackToDefault() {
        // Pinned: when no profiles are active, the dashboard shows
        // "default" — a non-empty fallback that signals "Spring is
        // running but no specific profile". Empty array would render
        // as a blank badge.
        when(environment.getActiveProfiles()).thenReturn(new String[]{});

        Map<String, Object> result = endpoint.report();

        @SuppressWarnings("unchecked")
        Map<String, Object> runtime = (Map<String, Object>) result.get("runtime");
        assertThat((String[]) runtime.get("activeProfiles"))
                .containsExactly("default");
    }

    @Test
    void report_runtimeSection_includesUptimeAndStartTime() {
        // Pinned: uptime metrics drive the "running for X" display on
        // the dashboard. uptimeSeconds + uptimeHuman + startedAt all
        // required for the panel rendering.
        Map<String, Object> result = endpoint.report();

        @SuppressWarnings("unchecked")
        Map<String, Object> runtime = (Map<String, Object>) result.get("runtime");
        assertThat(runtime).containsKeys("uptimeSeconds", "uptimeHuman", "startedAt");
        assertThat(runtime.get("uptimeSeconds")).isInstanceOf(Long.class);
        assertThat(runtime.get("uptimeHuman")).isInstanceOf(String.class);
    }

    @Test
    void report_runtimeSection_includesStartupDuration_whenTrackerHasFired() {
        // Pinned: when StartupTimeTracker has captured the boot time
        // (post-ApplicationReady), the dashboard shows "Started in 4.2s".
        // Pre-ready: trackerReturns 0 → the field is OMITTED entirely
        // (so the UI shows "—" instead of "0s").
        when(startupTimeTracker.getStartupDurationMs()).thenReturn(4_200L);

        Map<String, Object> result = endpoint.report();

        @SuppressWarnings("unchecked")
        Map<String, Object> runtime = (Map<String, Object>) result.get("runtime");
        assertThat(runtime).containsEntry("startupDurationMs", 4_200L);
        assertThat(runtime).containsEntry("startupDurationSeconds", 4.2);
    }

    @Test
    void report_runtimeSection_omitsStartupDuration_whenTrackerStillZero() {
        // Symmetric to above — pre-ApplicationReady, the field is
        // OMITTED rather than reported as 0. Pinned because the
        // dashboard distinguishes "still booting" (omit) from "instant
        // boot" (which would be 0 if rendered) — the omission IS the
        // signal.
        when(startupTimeTracker.getStartupDurationMs()).thenReturn(0L);

        Map<String, Object> result = endpoint.report();

        @SuppressWarnings("unchecked")
        Map<String, Object> runtime = (Map<String, Object>) result.get("runtime");
        assertThat(runtime).doesNotContainKeys("startupDurationMs", "startupDurationSeconds");
    }

    @Test
    void report_runtimeSection_isMarkedAvailable() {
        // Pinned: runtime section IS always available (no external
        // dependency to fail on). UI doesn't need to gate this section
        // behind {available: true} check.
        Map<String, Object> result = endpoint.report();

        @SuppressWarnings("unchecked")
        Map<String, Object> runtime = (Map<String, Object>) result.get("runtime");
        assertThat(runtime).containsEntry("available", true);
    }

    // ─── formatUptime branch coverage (via reflection — method is private) ───

    @Test
    void formatUptime_underAMinute_returnsSecondsOnly() throws Exception {
        // Sub-minute uptime ("running for 42s"). The runtime test above
        // already exercises this via the live JVM uptime, but we pin it
        // explicitly so a refactor that reorders the branches doesn't
        // regress it.
        assertThat(invokeFormatUptime(0)).isEqualTo("0s");
        assertThat(invokeFormatUptime(42)).isEqualTo("42s");
        assertThat(invokeFormatUptime(59)).isEqualTo("59s");
    }

    @Test
    void formatUptime_underAnHour_returnsMinutesAndSeconds() throws Exception {
        // 60..3599 seconds → "Xm Ys" form. Pinned : the seconds remainder
        // is shown alongside minutes ("5m 30s"), not stripped to "5m" —
        // matters because the dashboard's "running for" label must fit
        // both forms in the same column width.
        assertThat(invokeFormatUptime(60)).isEqualTo("1m 0s");
        assertThat(invokeFormatUptime(330)).isEqualTo("5m 30s");
        assertThat(invokeFormatUptime(3599)).isEqualTo("59m 59s");
    }

    @Test
    void formatUptime_overAnHour_returnsHoursAndMinutes_dropsSeconds() throws Exception {
        // ≥ 3600 → "Xh Ym". Pinned : seconds are deliberately dropped at
        // this range — minute precision is enough for "running for 8h 47m"
        // and avoids a noisy live-updating display.
        assertThat(invokeFormatUptime(3600)).isEqualTo("1h 0m");
        assertThat(invokeFormatUptime(3660)).isEqualTo("1h 1m");
        assertThat(invokeFormatUptime(31_727)).isEqualTo("8h 48m");
        // 86400s = exactly 24h → "24h 0m" (we don't roll into days).
        assertThat(invokeFormatUptime(86_400)).isEqualTo("24h 0m");
    }

    private String invokeFormatUptime(long seconds) throws Exception {
        var method = QualityReportEndpoint.class.getDeclaredMethod("formatUptime", long.class);
        method.setAccessible(true);
        return (String) method.invoke(endpoint, seconds);
    }

    // ─── buildJarLayersSection (via BOOT-INF/layers.idx test fixture) ────────

    @Test
    @SuppressWarnings("unchecked")
    void runtimeSection_jarLayers_parsesBootInfLayersIdxFromClasspath() {
        // src/test/resources/BOOT-INF/layers.idx is a synthetic Spring Boot
        // layered-jar index — the resource lands on the classpath at test
        // time and exercises the parser end-to-end. Pinned format :
        //   "- 'layer-name':"  → starts a new layer (header)
        //   "  - 'entry'"     → entry counter increment
        // Snapshot-dependencies has zero entries → "entries": 0 on its row.
        Map<String, Object> result = endpoint.report();
        Map<String, Object> runtime = (Map<String, Object>) result.get("runtime");

        List<Map<String, Object>> layers = (List<Map<String, Object>>) runtime.get("jarLayers");
        assertThat(layers).isNotNull().isNotEmpty();

        // Build a name → entries map from the layers list. AssertJ's
        // generic inference on the cast returns List<?> in some compiler
        // configs, so we accumulate manually to keep types explicit.
        Map<String, Integer> entryCounts = new java.util.LinkedHashMap<>();
        for (Map<String, Object> l : layers) {
            entryCounts.put((String) l.get("name"), (Integer) l.get("entries"));
        }

        assertThat(entryCounts.keySet()).containsExactly(
                "dependencies", "spring-boot-loader",
                "snapshot-dependencies", "application");
        assertThat(entryCounts).containsEntry("dependencies", 3);
        assertThat(entryCounts).containsEntry("spring-boot-loader", 1);
        assertThat(entryCounts).containsEntry("snapshot-dependencies", 0);
        assertThat(entryCounts).containsEntry("application", 2);
    }
}
