package org.iris.observability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link MaintenanceEndpoint} — the custom actuator
 * endpoint exposing PostgreSQL VACUUM operations. JdbcTemplate is
 * mocked; the test focuses on the SQL-string mapping + the strict
 * allowlist that prevents arbitrary SQL execution via the
 * {@code operation} parameter.
 */
@SuppressWarnings("java:S5853")  // Multi-assertion chain refactor deferred ; current shape reads better when subject + N separate assertions.
class MaintenanceEndpointTest {

    private JdbcTemplate jdbc;
    private MaintenanceEndpoint endpoint;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        endpoint = new MaintenanceEndpoint(jdbc);
    }

    @Test
    void run_vacuum_executesVacuumAnalyze() {
        Map<String, Object> result = endpoint.run("vacuum");

        verify(jdbc).execute("VACUUM ANALYZE");
        assertThat(result)
                .containsEntry("operation", "vacuum")
                .containsEntry("sql", "VACUUM ANALYZE")
                .containsEntry("status", "ok");
        assertThat(result).containsKey("durationMs");
        assertThat((Long) result.get("durationMs")).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void run_vacuumFull_executesVacuumFullAnalyze() {
        Map<String, Object> result = endpoint.run("vacuumFull");

        verify(jdbc).execute("VACUUM FULL ANALYZE");
        assertThat(result).containsEntry("sql", "VACUUM FULL ANALYZE");
    }

    @Test
    void run_vacuumVerbose_executesVacuumVerboseAnalyze() {
        Map<String, Object> result = endpoint.run("vacuumVerbose");

        verify(jdbc).execute("VACUUM VERBOSE ANALYZE");
        assertThat(result).containsEntry("sql", "VACUUM VERBOSE ANALYZE");
    }

    @Test
    void run_unknownOperation_throwsIllegalArgumentExceptionAndExecutesNoSql() {
        // CRITICAL security guard: the `operation` parameter is taken from
        // the actuator request body — without the allowlist switch, an
        // attacker could craft `operation: "ANALYZE; DROP TABLE customer;"`
        // and the SQL would land in jdbc.execute(). Test pins the allowlist.
        assertThatThrownBy(() -> endpoint.run("DROP TABLE customer"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DROP TABLE customer")
                .hasMessageContaining("Allowed: vacuum, vacuumFull, vacuumVerbose");
        verify(jdbc, never()).execute("DROP TABLE customer");
        // Sanity: jdbc was never called at all on the unknown branch.
        verify(jdbc, never()).execute(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void run_emptyOperation_throwsIllegalArgumentException() {
        // Edge case: empty string is "unknown" too — must reject same as
        // arbitrary SQL.
        assertThatThrownBy(() -> endpoint.run(""))
                .isInstanceOf(IllegalArgumentException.class);
        verify(jdbc, never()).execute(org.mockito.ArgumentMatchers.anyString());
    }
}
