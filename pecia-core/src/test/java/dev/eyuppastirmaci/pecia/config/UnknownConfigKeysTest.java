package dev.eyuppastirmaci.pecia.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.diagnostic.Warning;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class UnknownConfigKeysTest {

    private static final Path SOURCE = Path.of("").toAbsolutePath().resolve(".pecia.toml");

    private final PeciaConfigParser parser = new PeciaConfigParser();

    @Test
    void misspelledKeyIsReportedWithPositionAndSuggestionWithoutChangingSettings() {
        PeciaConfigParser.Parsed parsed = parser.parse("[chunk]\nmax_token = 128\n", SOURCE);

        assertEquals(
                List.of(new Warning(
                        Warning.Code.UNKNOWN_CONFIG_KEY,
                        SOURCE,
                        new Warning.Position(2, 1),
                        "unknown key chunk.max_token (did you mean chunk.max_tokens?)")),
                parsed.warnings());
        assertEquals(PeciaConfig.defaults(), parsed.config());
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
            nullValues = "none",
            value = {
                // Misspellings within a known table.
                "chunk.max_token, chunk.max_tokens",
                "chunk.max-tokens, chunk.max_tokens",
                "chunk.maxtokens, chunk.max_tokens",
                "chunk.max_tokesn, chunk.max_tokens",
                "chunk.Max_Tokens, chunk.max_tokens",
                "chunk.ovrlap_tokns, chunk.overlap_tokens",
                "index.includ, index.include",
                "index.includes, index.include",
                "index.exlude, index.exclude",
                "index.max_file_byte, index.max_file_bytes",
                "embed.concurency, embed.concurrency",
                "store.pth, store.path",
                "store.paths, store.path",
                "semantic.enable, semantic.enabled",
                "semantic.enabeld, semantic.enabled",
                "search.modes, search.mode",
                "search.mod, search.mode",
                // Misspelled tables and misplaced keys.
                "chunks.max_tokens, chunk.max_tokens",
                "chunks.overlap_tokens, chunk.overlap_tokens",
                "indx.include, index.include",
                "max_tokens, chunk.max_tokens",
                "max_token, chunk.max_tokens",
                "path, store.path",
                "include, index.include",
                "index.max_tokens, chunk.max_tokens",
                "chunk.path, store.path",
                "semantics.enabled, semantic.enabled",
                "serach.mode, search.mode",
                "semantic.mode, search.mode",
                "query.mode, search.mode",
                "mode, search.mode",
                "embed.enabled, semantic.enabled",
                "enabled, semantic.enabled",
                // Unrelated names get no guess.
                "index.max_size, none",
                "embed.threads, none",
                "chunk.overlap, none",
                "chunk.tokens, none",
                "unknown.key, none",
                "foo, none",
                "pa, none",
                "chunk.extra.foo, none",
                "search.type, none",
                "semantic.provider, none"
            })
    void suggestsTheClosestKnownKeyOrNothing(String path, String suggestion) {
        List<Warning> warnings = parser.parse(tomlSetting(path), SOURCE).warnings();
        String expected = "unknown key " + path + (suggestion == null ? "" : " (did you mean " + suggestion + "?)");

        assertEquals(List.of(expected), messages(warnings));
    }

    @ParameterizedTest
    @ValueSource(strings = {"semantic.model", "embed.model", "search.model", "model"})
    void modelKeysNameTheOnlySupportedModelInsteadOfSuggestingSearchMode(String path) {
        List<Warning> warnings = parser.parse(tomlSetting(path), SOURCE).warnings();

        assertEquals(
                List.of("unknown key " + path + " (all-MiniLM-L6-v2 is the only supported embedding model)"),
                messages(warnings));
    }

    @Test
    void misspelledTableReportsEachKeyWithItsOwnSuggestion() {
        List<Warning> warnings = parser.parse("[chunks]\nmax_tokens = 128\noverlap_tokens = 16\n", SOURCE)
                .warnings();

        assertEquals(
                List.of(
                        "2:1 unknown key chunks.max_tokens (did you mean chunk.max_tokens?)",
                        "3:1 unknown key chunks.overlap_tokens (did you mean chunk.overlap_tokens?)"),
                located(warnings));
    }

    @Test
    void emptyUnknownTablesAreReportedAsTables() {
        assertEquals(
                List.of("1:1 unknown table chunks (did you mean chunk?)"),
                located(parser.parse("[chunks]\n", SOURCE).warnings()));
        assertEquals(
                List.of("1:1 unknown table unknown"),
                located(parser.parse("[unknown]\n", SOURCE).warnings()));
        assertEquals(
                List.of("2:1 unknown table chunk.extra"),
                located(parser.parse("[chunk]\nextra = {}\n", SOURCE).warnings()));
    }

    @Test
    void leavesInsideNestedUnknownTablesAreReportedWithTheirFullPath() {
        String toml = "[chunk]\nmax_tokens = 128\n\n[chunk.extra]\nfoo = 1\nbar = 2\n";

        assertEquals(
                List.of("5:1 unknown key chunk.extra.foo", "6:1 unknown key chunk.extra.bar"),
                located(parser.parse(toml, SOURCE).warnings()));
    }

    @Test
    void dottedKeyTypoGetsASuggestion() {
        assertEquals(
                List.of("2:1 unknown key chunk.max.tokens (did you mean chunk.max_tokens?)"),
                located(parser.parse("[chunk]\nmax.tokens = 1\n", SOURCE).warnings()));
    }

    @Test
    void inlineTableEntriesAreReportedAtTheirOwnColumns() {
        assertEquals(
                List.of("1:29 unknown key chunk.foo"),
                located(parser.parse("chunk = { max_tokens = 128, foo = 1 }\n", SOURCE)
                        .warnings()));
        assertEquals(
                List.of("2:11 unknown key chunk.extra.a"),
                located(parser.parse("[chunk]\nextra = { a = 1 }\n", SOURCE).warnings()));
    }

    @Test
    void arraysOfTablesAreReportedOnceAtTheirFirstHeader() {
        String toml = "[[chunk.rules]]\nx = 1\n\n[[chunk.rules]]\nx = 2\n";

        assertEquals(
                List.of("1:1 unknown key chunk.rules"),
                located(parser.parse(toml, SOURCE).warnings()));
    }

    @Test
    void segmentsThatAreNotBareKeysAreQuotedAndKeepNonAsciiLetters() {
        String toml = "[chunk]\n\"max.tokens\" = 1\n\"maks_t\u00f6kens\" = 2\n\"say \\\"hi\\\"\" = 3\n";

        assertEquals(
                List.of(
                        "2:1 unknown key chunk.\"max.tokens\" (did you mean chunk.max_tokens?)",
                        "3:1 unknown key chunk.\"maks_t\u00f6kens\"",
                        "4:1 unknown key chunk.\"say \\\"hi\\\"\""),
                located(parser.parse(toml, SOURCE).warnings()));
    }

    @Test
    void positionsFollowIndentationAndCrlfLineEndings() {
        assertEquals(
                List.of("2:5 unknown key chunk.max_token (did you mean chunk.max_tokens?)"),
                located(parser.parse("[chunk]\n    max_token = 1\n", SOURCE).warnings()));
        assertEquals(
                List.of("3:2 unknown key chunk.max_token (did you mean chunk.max_tokens?)"),
                located(parser.parse("# note\r\n[chunk]\r\n\tmax_token = 1\r\n", SOURCE)
                        .warnings()));
        assertEquals(
                List.of("1:1 unknown key zeta"),
                located(parser.parse("\uFEFFzeta = 1\n", SOURCE).warnings()));
    }

    @Test
    void warningsAreOrderedBySourcePositionRatherThanTableNesting() {
        // A depth-first walk would report chunk.extra.foo before store.paths.
        String toml = "zeta = 1\n\n[chunk]\nmax_token = 1\n\n[store]\npaths = \"x\"\n\n[chunk.extra]\nfoo = 1\n";

        assertEquals(
                List.of(
                        "1:1 unknown key zeta",
                        "4:1 unknown key chunk.max_token (did you mean chunk.max_tokens?)",
                        "7:1 unknown key store.paths (did you mean store.path?)",
                        "10:1 unknown key chunk.extra.foo"),
                located(parser.parse(toml, SOURCE).warnings()));
    }

    @Test
    void knownKeysAndTheDefaultTemplateProduceNoWarnings() {
        assertEquals(
                List.of(),
                parser.parse(new PeciaConfigFile().defaultToml(), SOURCE).warnings());
        assertEquals(
                List.of(),
                parser.parse("chunk.max_tokens = 128\n[index]\n", SOURCE).warnings());
        assertEquals(List.of(), parser.parse("", SOURCE).warnings());
    }

    @Test
    void invalidConfigurationStillFailsInsteadOfWarning() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse("[chunk]\nmax_tokens = \"many\"\nmax_token = 1\n", SOURCE));

        assertTrue(failure.getMessage().contains("chunk.max_tokens must be an integer"), failure.getMessage());
    }

    @Test
    void distanceCountsEditsAndAdjacentTranspositions() {
        assertEquals(0, KeySuggestion.distance("path", "path"));
        assertEquals(1, KeySuggestion.distance("max_token", "max_tokens"));
        assertEquals(1, KeySuggestion.distance("max_tokesn", "max_tokens"));
        assertEquals(2, KeySuggestion.distance("include", "exclude"));
        assertEquals(4, KeySuggestion.distance("", "path"));
    }

    /** Writes {@code a.b.c = 1} as a table header and a key, so the key sits on line 2 unless it is at the root. */
    private static String tomlSetting(String path) {
        List<String> segments = Arrays.asList(path.split("\\."));

        if (segments.size() == 1) {
            return path + " = 1\n";
        }

        return "[" + String.join(".", segments.subList(0, segments.size() - 1)) + "]\n" + segments.getLast() + " = 1\n";
    }

    private static List<String> messages(List<Warning> warnings) {
        return warnings.stream().map(Warning::message).toList();
    }

    private static List<String> located(List<Warning> warnings) {
        return warnings.stream()
                .map(warning ->
                        warning.position().line() + ":" + warning.position().column() + " " + warning.message())
                .toList();
    }
}
