package org.iris.customer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CustomerDiagnosticsController} covering the three
 * observability-demo endpoints (stream / slow-query / export) extracted
 * from {@code CustomerController} on 2026-04-22 (Phase B-7-7).
 *
 * <p>Pure unit tests — no Spring context, no PostgreSQL. Verifies the
 * controller wiring + the slow-query 10s cap (a misuse safeguard) + the
 * CSV export header + payload shape, including the embedded-quote
 * escaping that the export does inline.
 */
@SuppressWarnings("java:S5853")  // Multi-assertion chain refactor deferred ; current shape reads better when subject + N separate assertions.
class CustomerDiagnosticsControllerTest {

    private CustomerService service;
    private SseEmitterRegistry sseRegistry;
    private CustomerDiagnosticsController controller;

    @BeforeEach
    void setUp() {
        service = mock(CustomerService.class);
        sseRegistry = mock(SseEmitterRegistry.class);
        controller = new CustomerDiagnosticsController(service, sseRegistry);
    }

    // ── /stream ────────────────────────────────────────────────────────────────

    @Test
    void stream_delegatesToSseRegistry() {
        SseEmitter expected = new SseEmitter();
        when(sseRegistry.register()).thenReturn(expected);

        SseEmitter result = controller.stream();

        assertThat(result).isSameAs(expected);
        verify(sseRegistry).register();
    }

    // ── /slow-query ────────────────────────────────────────────────────────────

    @Test
    void slowQuery_passesRequestedDurationToService() {
        var result = controller.slowQuery(2.0);

        verify(service).simulateSlowQuery(2.0);
        assertThat(result).containsEntry("status", "completed");
        assertThat(result).containsEntry("duration", "2.0s");
    }

    @Test
    void slowQuery_capsAt10Seconds() {
        // Misuse-safety guard — caller could otherwise tie up a connection
        // for hours. Tests both that the service receives the capped value AND
        // that the response reports the cap (caller knows their value was clipped).
        var result = controller.slowQuery(60.0);

        verify(service).simulateSlowQuery(10.0);
        assertThat(result).containsEntry("duration", "10.0s");
    }

    @Test
    void slowQuery_acceptsValuesBelowCap() {
        var result = controller.slowQuery(0.5);

        verify(service).simulateSlowQuery(0.5);
        assertThat(result).containsEntry("duration", "0.5s");
    }

    // ── /db-failure ────────────────────────────────────────────────────────────

    @Test
    void dbFailure_delegatesToServiceAndPropagatesException() {
        // The service throws DataAccessException when the bad SQL hits
        // Postgres ; the controller is a thin pass-through. We verify
        // both the delegation AND the exception surface so the
        // framework's @ExceptionHandler can map it to a 500 ProblemDetail.
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException(
                "deliberate-chaos relation \"iris_nonexistent_table_for_chaos\" does not exist"))
                .when(service).simulateDbFailure();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.dbFailure())
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageContaining("nonexistent");

        verify(service).simulateDbFailure();
    }

    // ── /kafka-timeout ─────────────────────────────────────────────────────────

    @Test
    void kafkaTimeout_returns504WithSyntheticBody() {
        when(service.simulateKafkaTimeout()).thenReturn(Map.of(
                "scenario", "kafka-timeout",
                "synthetic", "true",
                "detail", "504 — synthetic, no broker call"));

        ResponseEntity<Map<String, String>> response = controller.kafkaTimeout();

        assertThat(response.getStatusCode().value()).isEqualTo(504);
        assertThat(response.getBody()).containsEntry("scenario", "kafka-timeout");
        assertThat(response.getBody()).containsEntry("synthetic", "true");
        verify(service).simulateKafkaTimeout();
    }

    // ── /export ────────────────────────────────────────────────────────────────

    @Test
    void exportCsv_setsContentDispositionAttachmentHeader() {
        when(service.findAllForExport()).thenReturn(List.of());

        ResponseEntity<StreamingResponseBody> response = controller.exportCsv();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=customers.csv");
        assertThat(response.getHeaders().getContentType()).hasToString("text/csv");
    }

    @Test
    void exportCsv_writesHeaderAndOneRowPerCustomer() throws Exception {
        Customer alice = new Customer(1L, "Alice", "alice@example.com",
                Instant.parse("2026-04-22T10:00:00Z"));
        Customer bob = new Customer(2L, "Bob", "bob@example.com",
                Instant.parse("2026-04-22T11:00:00Z"));
        when(service.findAllForExport()).thenReturn(List.of(alice, bob));

        ResponseEntity<StreamingResponseBody> response = controller.exportCsv();
        var body = response.getBody();
        assertThat(body).isNotNull();
        var out = new ByteArrayOutputStream();
        body.writeTo(out);
        String csv = out.toString(java.nio.charset.StandardCharsets.UTF_8);

        // Header line + 2 data lines (no trailing empty line beyond `%n`)
        assertThat(csv).startsWith("id,name,email,created_at\n");
        assertThat(csv).contains("1,\"Alice\",\"alice@example.com\",2026-04-22T10:00:00Z");
        assertThat(csv).contains("2,\"Bob\",\"bob@example.com\",2026-04-22T11:00:00Z");
    }

    @Test
    void exportCsv_escapesEmbeddedQuotesInNamesAndEmails() {
        // Inline CSV escaping: name with a `"` must be doubled-up so the
        // resulting CSV stays valid (RFC 4180). Same for email (rare in
        // practice but the same code path handles both).
        Customer hacker = new Customer(99L, "Bobby \"Tables\" O'Reilly",
                "weird\"quote@example.com", Instant.parse("2026-04-22T12:00:00Z"));
        when(service.findAllForExport()).thenReturn(List.of(hacker));

        ResponseEntity<StreamingResponseBody> response = controller.exportCsv();
        var body = response.getBody();
        assertThat(body).isNotNull();
        var out = new ByteArrayOutputStream();
        try {
            body.writeTo(out);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        String csv = out.toString(java.nio.charset.StandardCharsets.UTF_8);

        // `"` inside a quoted field becomes `""`
        assertThat(csv).contains("\"Bobby \"\"Tables\"\" O'Reilly\"");
        assertThat(csv).contains("\"weird\"\"quote@example.com\"");
    }
}
