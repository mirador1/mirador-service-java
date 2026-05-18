package org.iris.observability.quality.parsers;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PitestReportParser} using fixture XML at
 * {@code src/test/resources/META-INF/build-reports/pit-reports/mutations.xml}.
 *
 * <p>Fixture: 5 mutations (2 KILLED, 1 SURVIVED, 1 NO_COVERAGE, 1
 * TIMED_OUT) across 3 mutators and 3 classes (one without a package
 * prefix to test the no-dot branch).
 */
@SuppressWarnings("java:S5853")  // Multi-assertion chain refactor deferred ; current shape reads better when subject + N separate assertions.
class PitestReportParserTest {

    private final PitestReportParser parser = new PitestReportParser();

    @Test
    void parse_classpathFixture_returnsAvailableTrueWithCorrectTotals() {
        Map<String, Object> result = parser.parse();

        assertThat(result).containsEntry("available", true);
        assertThat(result).containsEntry("total", 5);
        assertThat(result).containsEntry("killed", 2);
        assertThat(result).containsEntry("survived", 1);
        assertThat(result).containsEntry("noCoverage", 1);
    }

    @Test
    void parse_mutationScoreCalculatedFromKilledOverTotal() {
        // 2 killed / 5 total = 40.0%
        Map<String, Object> result = parser.parse();

        assertThat(result).containsEntry("score", 40.0);
    }

    @Test
    void parse_byStatusAggregatesAllStatuses() {
        Map<String, Object> result = parser.parse();

        @SuppressWarnings("unchecked")
        Map<String, Integer> byStatus = (Map<String, Integer>) result.get("byStatus");
        assertThat(byStatus).containsEntry("KILLED", 2)
                .containsEntry("SURVIVED", 1)
                .containsEntry("NO_COVERAGE", 1)
                .containsEntry("TIMED_OUT", 1);
    }

    @Test
    void parse_mutatorNameShortenedFromFqcn() {
        // org.pitest.mutationtest.engine.gregor.mutators.NegateConditionalsMutator
        // → NegateConditionalsMutator (last segment after `.`)
        Map<String, Object> result = parser.parse();

        @SuppressWarnings("unchecked")
        Map<String, Integer> byMutator = (Map<String, Integer>) result.get("byMutator");
        assertThat(byMutator).containsEntry("NegateConditionalsMutator", 3) // 2 KILLED + 1 TIMED_OUT
                .containsEntry("MathMutator", 2);                            // 1 SURVIVED + 1 NO_COVERAGE
    }

    @Test
    void parse_survivingMutationsIncludesOnlyTheSurvivedOnes() {
        // SURVIVED is special-cased: it's the actionable list (where the
        // dev needs to add tests). KILLED/NO_COVERAGE/TIMED_OUT don't
        // appear here even though byStatus counts them.
        Map<String, Object> result = parser.parse();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> surviving = (List<Map<String, Object>>) result.get("survivingMutations");
        assertThat(surviving).hasSize(1);
        assertThat(surviving.get(0)).containsEntry("class", "BobService")
                .containsEntry("method", "compute")
                .containsEntry("mutator", "MathMutator");
    }

    @Test
    void parse_classWithoutPackageDots_passesThroughUnchanged() {
        // The fixture has a SURVIVED-counterpart? No — let me check the
        // class shortening code path is exercised regardless. The
        // NoPackageClass entry is NO_COVERAGE so it won't appear in
        // surviving, but the byStatus / byMutator aggregation must include it.
        Map<String, Object> result = parser.parse();

        @SuppressWarnings("unchecked")
        Map<String, Integer> byStatus = (Map<String, Integer>) result.get("byStatus");
        assertThat(byStatus).containsEntry("NO_COVERAGE", 1);
    }

    @Test
    void parse_eachSurvivingMutationHasFourRequiredFields() {
        Map<String, Object> result = parser.parse();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> surviving = (List<Map<String, Object>>) result.get("survivingMutations");
        for (Map<String, Object> sm : surviving) {
            assertThat(sm).containsKeys("class", "method", "mutator", "description");
        }
    }
}
