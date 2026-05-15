package org.iris.observability.quality.providers;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LicensesSectionProvider} using a fixture
 * THIRD-PARTY.txt at
 * {@code src/test/resources/META-INF/build-reports/THIRD-PARTY.txt}.
 *
 * <p>Fixture covers:
 * <ul>
 *   <li>Permissive licenses (Apache 2.0, MIT) → 3 entries</li>
 *   <li>Restricted licenses (LGPL, GPL) → 2 entries flagged incompatible</li>
 *   <li>Two malformed lines (no paren / no coordinates) — must be skipped</li>
 * </ul>
 */
@SuppressWarnings("java:S5853")  // Multi-assertion chain refactor deferred ; current shape reads better when subject + N separate assertions.
class LicensesSectionProviderTest {

    private final LicensesSectionProvider provider = new LicensesSectionProvider();

    @Test
    void parse_classpathFixture_returnsAvailableTrueWithCorrectTotal() {
        Map<String, Object> result = provider.parse();

        assertThat(result).containsEntry("available", true);
        // 5 valid lines + 2 malformed (skipped) → total = 5
        assertThat(result).containsEntry("total", 5);
    }

    @Test
    void parse_incompatibleCountReflectsRestrictedLicenses() {
        // Restricted = GPL, AGPL, LGPL, CDDL, EPL substrings.
        // Fixture: LGPL 2.1 + GPL v3 → 2 incompatible.
        Map<String, Object> result = provider.parse();

        assertThat(result).containsEntry("incompatibleCount", 2);
    }

    @Test
    void parse_dependenciesIncludeGroupArtifactVersionLicenseAndIncompatibleFlag() {
        Map<String, Object> result = provider.parse();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> deps = (List<Map<String, Object>>) result.get("dependencies");
        assertThat(deps).hasSize(5);

        Map<String, Object> hibernate = deps.stream()
                .filter(d -> "hibernate-core".equals(d.get("artifact")))
                .findFirst().orElseThrow();
        assertThat(hibernate)
                .containsEntry("group", "org.hibernate.orm")
                .containsEntry("version", "6.4.0.Final")
                .containsEntry("license", "LGPL 2.1")
                .containsEntry("incompatible", true);
    }

    @Test
    void parse_apache2_0LicenseIsCompatible() {
        // Apache License 2.0 is permissive — must NOT be flagged
        // incompatible (would trigger a false positive in the dashboard
        // "incompatible licenses" panel).
        Map<String, Object> result = provider.parse();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> deps = (List<Map<String, Object>>) result.get("dependencies");
        Map<String, Object> springBoot = deps.stream()
                .filter(d -> "spring-boot-starter".equals(d.get("artifact")))
                .findFirst().orElseThrow();
        assertThat(springBoot).containsEntry("incompatible", false);
    }

    @Test
    void parse_licensesSummarySortedByCountDescending() {
        Map<String, Object> result = provider.parse();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> licenses = (List<Map<String, Object>>) result.get("licenses");
        // Apache 2.0 appears 2x → leads. Then MIT (1) + LGPL (1) + GPL v3 (1).
        assertThat(licenses).isNotEmpty();
        assertThat(licenses.get(0))
                .containsEntry("license", "Apache License 2.0")
                .containsEntry("count", 2);
    }

    @Test
    void parse_licensesSummary_marksRestrictedAsIncompatible() {
        Map<String, Object> result = provider.parse();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> licenses = (List<Map<String, Object>>) result.get("licenses");
        Map<String, Object> lgpl = licenses.stream()
                .filter(l -> "LGPL 2.1".equals(l.get("license")))
                .findFirst().orElseThrow();
        assertThat(lgpl).containsEntry("incompatible", true);
    }

    @Test
    void parse_malformedLinesAreSkipped_notCrashed() {
        // Pinned: a stray "malformed-line-without-paren" or "(MIT License)
        // malformed-no-coords" must not derail the whole parse. The skip
        // contract is what lets us be tolerant of generator drift.
        Map<String, Object> result = provider.parse();

        // If the malformed lines had been processed wrong, total would be
        // > 5 OR the parse would have errored (available: false).
        assertThat(result).containsEntry("available", true);
        assertThat(result).containsEntry("total", 5);
    }
}
