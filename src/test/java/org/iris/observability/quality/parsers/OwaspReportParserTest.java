package org.iris.observability.quality.parsers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the public static helpers in {@link OwaspReportParser}.
 *
 * <p>Focus on {@code cleanCveId} + {@code cleanCveDescription} which are
 * pure functions that take the noisy NVD/RetireJS payloads and produce
 * UI-displayable strings. The full {@code parse()} method reads files
 * from disk and is exercised separately by the smoke test.
 */
@SuppressWarnings("java:S5853")  // Multi-assertion chain refactor deferred ; current shape reads better when subject + N separate assertions.
class OwaspReportParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── cleanCveId ────────────────────────────────────────────────────────────

    @Test
    void cleanCveId_validCveFormat_returnedVerbatim() {
        assertThat(OwaspReportParser.cleanCveId("CVE-2024-12345", null))
                .isEqualTo("CVE-2024-12345");
        assertThat(OwaspReportParser.cleanCveId("CVE-2023-9", null))
                .isEqualTo("CVE-2023-9");
    }

    @Test
    void cleanCveId_nullOrBlank_returnsUnknown() {
        // Defensive: never crash on missing data, surface the gap as "UNKNOWN"
        // so the dashboard renders a row instead of crashing the whole table.
        assertThat(OwaspReportParser.cleanCveId(null, null)).isEqualTo("UNKNOWN");
        assertThat(OwaspReportParser.cleanCveId("", null)).isEqualTo("UNKNOWN");
        assertThat(OwaspReportParser.cleanCveId("   ", null)).isEqualTo("UNKNOWN");
    }

    @Test
    void cleanCveId_ghsaInReferences_extractsTheGhsaId() throws Exception {
        // RetireJS advisories typically have a Markdown name + the real
        // GHSA-xxxx ID buried in the references array. The helper must pull
        // it out for clean rendering.
        JsonNode refs = MAPPER.readTree("""
                [
                  {"url": "https://example.com/notice"},
                  {"url": "https://github.com/foo/bar/security/advisories/GHSA-aaaa-bbbb-cccc"}
                ]
                """);

        assertThat(OwaspReportParser.cleanCveId("# Some Markdown Advisory", refs))
                .isEqualTo("GHSA-aaaa-bbbb-cccc");
    }

    @Test
    void cleanCveId_noGhsaAndNonCveName_returnsTrimmedFirstLine() {
        // Fallback: name is plain text — return it (truncated at 40 chars).
        assertThat(OwaspReportParser.cleanCveId("Cross-site scripting in foo", null))
                .isEqualTo("Cross-site scripting in foo");
    }

    @Test
    void cleanCveId_multilineMarkdown_skipsHeadersAndFenceMarkersOnly() {
        // Pinned behaviour: the helper filters lines that START with `#` or
        // ``` (the fence marker line itself), but does NOT track "inside a
        // code block" state — so content INSIDE a fenced block is still
        // picked up if it's the first non-marker line. Acceptable trade-off
        // for the helper's simplicity; if it ever matters in production
        // the input shape is a Markdown advisory which rarely puts the
        // description inside a code block.
        String name = """
                # Title heading
                Real description starts here
                """;

        assertThat(OwaspReportParser.cleanCveId(name, null))
                .isEqualTo("Real description starts here");
    }

    @Test
    void cleanCveId_longFirstLine_truncatedWithEllipsis() {
        String longName = "This is a very long advisory description that exceeds the forty character display limit by far";

        String result = OwaspReportParser.cleanCveId(longName, null);

        assertThat(result).hasSize(41); // 40 chars + the ellipsis
        assertThat(result).endsWith("…");
    }

    // ── cleanCveDescription ───────────────────────────────────────────────────

    @Test
    void cleanCveDescription_plainText_returnedAsIs() {
        assertThat(OwaspReportParser.cleanCveDescription("Simple description."))
                .isEqualTo("Simple description.");
    }

    @Test
    void cleanCveDescription_nullOrBlank_returnsEmpty() {
        assertThat(OwaspReportParser.cleanCveDescription(null)).isEmpty();
        assertThat(OwaspReportParser.cleanCveDescription("")).isEmpty();
        assertThat(OwaspReportParser.cleanCveDescription("   ")).isEmpty();
    }

    @Test
    void cleanCveDescription_strippMarkdownEmphasisAndCode() {
        // Markdown emphasis (**bold**, *italic*, `code`) and links must be
        // stripped — the dashboard renders plain text, not Markdown.
        assertThat(OwaspReportParser.cleanCveDescription("**bold** and *italic* and `code`"))
                .isEqualTo("bold and italic and code");
    }

    @Test
    void cleanCveDescription_stripsMarkdownLinkButKeepsLabel() {
        assertThat(OwaspReportParser.cleanCveDescription("See [advisory](https://example.com/x) for details"))
                .isEqualTo("See advisory for details");
    }

    @Test
    void cleanCveDescription_stripsHtmlTags() {
        // HTML can appear in NVD descriptions for old CVEs.
        assertThat(OwaspReportParser.cleanCveDescription("Issue in <code>foo</code> module"))
                .isEqualTo("Issue in foo module");
    }

    @Test
    void cleanCveDescription_skipsHeadersAndFenceMarkers() {
        // Same shape-of-input note as cleanCveId: helper skips lines starting
        // with `#`, ```, or `- ` but doesn't track "inside fence" state.
        String md = """
                # Vulnerability summary
                Actual description after the noise
                """;

        assertThat(OwaspReportParser.cleanCveDescription(md))
                .isEqualTo("Actual description after the noise");
    }

    @Test
    void cleanCveDescription_truncatesLongTextAt200Chars() {
        String longDesc = "x".repeat(250);

        String result = OwaspReportParser.cleanCveDescription(longDesc);

        assertThat(result).hasSize(201); // 200 + ellipsis
        assertThat(result).endsWith("…");
    }
}
