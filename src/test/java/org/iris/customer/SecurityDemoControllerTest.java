package org.iris.customer;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SecurityDemoController} — verifies the OWASP demo
 * endpoints' SHAPE (response keys, query format, escaping) without
 * exercising the deliberately-vulnerable behaviour. The controller exists
 * to demonstrate vulnerabilities; tests guard against the demo's
 * EDUCATIONAL contract drifting (e.g. someone "fixing" the vulnerable
 * endpoint silently — the UI's diff view would break).
 *
 * <p>JdbcTemplate is mocked: actual DB access is integration territory.
 * Each test runs in milliseconds.
 */
@SuppressWarnings({"java:S125", "java:S5853"})  // S125: prose comments with code-like glyphs (arrows, backticks, parens) — not actual commented-out code. S5853: multi-assertion chain refactor deferred ; current shape reads better with subject + N separate assertions.
class SecurityDemoControllerTest {

    private JdbcTemplate jdbcTemplate;
    private SecurityDemoController controller;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        controller = new SecurityDemoController(jdbcTemplate);
    }

    // ── /sqli-vulnerable ──────────────────────────────────────────────────────

    @Test
    void sqliVulnerable_concatenatesInputIntoSql() {
        // Concatenation IS the vulnerability — guard that the test stays a
        // demo (not "fixed" silently into a parameterized form, which would
        // make the UI's diff-with-safe meaningless).
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of());

        Map<String, Object> result = controller.sqliVulnerable("Alice' OR '1'='1");

        assertThat(result).containsKey("query");
        assertThat((String) result.get("query"))
                .startsWith("SELECT id, name, email FROM customer WHERE name = '")
                .contains("Alice' OR '1'='1"); // literal injection visible in response
        verify(jdbcTemplate).queryForList(
                "SELECT id, name, email FROM customer WHERE name = 'Alice' OR '1'='1'");
        assertThat(result).containsEntry("vulnerability",
                "String concatenation — input is NOT sanitized");
    }

    // ── /sqli-safe ────────────────────────────────────────────────────────────

    @Test
    void sqliSafe_usesParameterizedQuery() {
        // Safe variant must use the `?` placeholder + pass the raw input as
        // a separate argument so the JDBC driver treats it as data.
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        Map<String, Object> result = controller.sqliSafe("Alice");

        assertThat(result).containsEntry("query",
                "SELECT id, name, email FROM customer WHERE name = ?");
        verify(jdbcTemplate).queryForList(
                "SELECT id, name, email FROM customer WHERE name = ?", "Alice");
    }

    // ── /xss-vulnerable ───────────────────────────────────────────────────────

    @Test
    void xssVulnerable_reflectsRawHtmlWithoutEscaping() {
        // The vulnerable endpoint MUST echo input verbatim into the body.
        // If someone "fixes" this by adding HtmlUtils.htmlEscape(), the demo
        // collapses — the UI shows identical output for vulnerable vs safe.
        // Note: the vulnerable response ALSO contains an escaped-form hint in
        // its static help text ("Try: ?name=&lt;script&gt;..."), so we can't
        // assert absence of `&lt;script&gt;` globally; check only that the
        // user input is echoed verbatim into the `Hello,` greeting.
        String payload = "<script>alert('XSS')</script>";

        String html = controller.xssVulnerable(payload);

        assertThat(html).contains("Hello, " + payload + "!");
    }

    @Test
    void xssVulnerable_doesNotEscapeUserInputInGreeting() {
        // Stronger guard: the greeting line must contain the LITERAL `<script>`
        // open tag, proving the input wasn't HTML-encoded before insertion.
        String html = controller.xssVulnerable("<b>bold</b>");

        assertThat(html).contains("<h1>Hello, <b>bold</b>!</h1>");
    }

    // ── /xss-safe ─────────────────────────────────────────────────────────────

    @Test
    void xssSafe_htmlEncodesInput() {
        String payload = "<script>alert('XSS')</script>";

        String html = controller.xssSafe(payload);

        // Spring HtmlUtils.htmlEscape escapes < > " ' &
        assertThat(html).contains("Hello, &lt;script&gt;alert(&#39;XSS&#39;)&lt;/script&gt;!");
        assertThat(html).doesNotContain("<script>"); // raw tag stripped
    }

    @Test
    void xssSafe_preservesPlainTextIntact() {
        // Side-effect free — plain ASCII passes through unchanged.
        String html = controller.xssSafe("Alice");

        assertThat(html).contains("Hello, Alice!");
    }

    // ── /cors-info ────────────────────────────────────────────────────────────

    @Test
    void corsInfo_reflectsRequestOrigin() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Origin")).thenReturn("https://evil.example.com");

        Map<String, Object> result = controller.corsInfo(request);

        assertThat(result).containsEntry("yourOrigin", "https://evil.example.com");
        assertThat(result).containsKey("currentOriginPolicy");
        assertThat(result).containsKey("dangerousConfig");
        assertThat(result).containsKey("risk");
        assertThat(result).containsKey("fix");
    }

    @Test
    void corsInfo_handlesMissingOriginHeader() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Origin")).thenReturn(null);

        Map<String, Object> result = controller.corsInfo(request);

        // The controller must coalesce a missing Origin header to a placeholder
        // string rather than passing null to Map.of() (which would NPE).
        // Same-origin requests + curl probes don't send the header — the demo
        // endpoint shouldn't 500 just because of a benign caller.
        assertThat(result).containsEntry("yourOrigin",
                "(none — same-origin or non-browser caller)");
    }

    // ── /idor-vulnerable ──────────────────────────────────────────────────────

    @Test
    void idorVulnerable_returnsAnyCustomerWithoutOwnershipCheck() {
        // Vulnerability = no auth check, no ownership filter on the WHERE.
        // Test guards against "fix" that adds those silently.
        when(jdbcTemplate.queryForList(anyString(), eq(42L)))
                .thenReturn(List.of(Map.of("id", 42, "name", "Alice", "email", "alice@example.com")));

        Map<String, Object> result = controller.idorVulnerable(42L);

        assertThat(result).containsEntry("requestedId", 42L);
        assertThat(result).containsEntry("owaspCategory",
                "A01:2021 — Broken Object Level Authorization (BOLA/IDOR)");
        verify(jdbcTemplate).queryForList(
                "SELECT id, name, email, created_at FROM customer WHERE id = ?", 42L);
    }

    // ── /idor-safe ────────────────────────────────────────────────────────────

    @Test
    void idorSafe_documentsOwnershipPattern() {
        // /idor-safe doesn't actually QUERY — it just returns the documentation
        // of the safe pattern. So no jdbcTemplate interaction expected.
        Map<String, Object> result = controller.idorSafe(42L);

        assertThat(result).containsEntry("requestedId", 42L);
        assertThat((String) result.get("safeQuery")).contains("AND created_by = :currentAuthenticatedUser");
        assertThat((String) result.get("springAnnotation")).contains("@PreAuthorize");
    }

    // ── /headers ──────────────────────────────────────────────────────────────

    @Test
    void headersInfo_listsTheSevenOwaspHeaders() {
        Map<String, Object> result = controller.headersInfo();

        @SuppressWarnings("unchecked")
        List<Map<String, String>> headers = (List<Map<String, String>>) result.get("headers");
        assertThat(headers).hasSize(7);
        assertThat(headers).extracting("name").containsExactlyInAnyOrder(
                "X-Content-Type-Options",
                "X-Frame-Options",
                "X-XSS-Protection",
                "Referrer-Policy",
                "Content-Security-Policy",
                "Permissions-Policy",
                "Strict-Transport-Security");
    }

    @Test
    void headersInfo_eachEntryHasNameExpectedExplanation() {
        // Schema check — UI reads { name, expected, explanation } per row;
        // a missing field would render an empty cell silently.
        Map<String, Object> result = controller.headersInfo();

        @SuppressWarnings("unchecked")
        List<Map<String, String>> headers = (List<Map<String, String>>) result.get("headers");
        for (Map<String, String> h : headers) {
            assertThat(h).containsKeys("name", "expected", "explanation");
            assertThat(h.get("name")).isNotBlank();
            assertThat(h.get("expected")).isNotBlank();
            assertThat(h.get("explanation")).isNotBlank();
        }
    }
}
