package dev.eyuppastirmaci.pecia.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class PeciaConfigParserTest {

    private final PeciaConfigParser parser = new PeciaConfigParser();

    @Test
    void parsesAFullConfig() {
        String toml = """
            [index]
            include = ["**/*.rs"]
            exclude = ["**/build/**"]
            max_file_bytes = 4096

            [chunk]
            max_tokens = 512
            overlap_tokens = 64

            [semantic]
            enabled = true

            [embed]
            concurrency = 4

            [search]
            mode = "hybrid"

            [store]
            path = "custom/index.db"
            """;

        PeciaConfig config = parser.parse(toml);

        assertEquals(List.of("**/*.rs"), config.include());
        assertEquals(List.of("**/build/**"), config.exclude());
        assertEquals(4096, config.maxFileBytes());
        assertEquals(512, config.maxTokens());
        assertEquals(64, config.overlapTokens());
        assertEquals(4, config.embedConcurrency());
        assertEquals("custom/index.db", config.storePath());
        assertTrue(config.semanticEnabled());
        assertEquals(SearchMode.HYBRID, config.searchMode());
    }

    @Test
    void missingKeysFallBackToDefaults() {
        String toml = """
            [chunk]
            max_tokens = 512
            """;

        PeciaConfig config = parser.parse(toml);

        assertEquals(512, config.maxTokens());
        assertEquals(PeciaConfig.defaults().overlapTokens(), config.overlapTokens());
        assertEquals(PeciaConfig.defaults().include(), config.include());
        assertEquals(PeciaConfig.defaults().maxFileBytes(), config.maxFileBytes());
        assertEquals(PeciaConfig.defaults().storePath(), config.storePath());
        assertFalse(config.semanticEnabled());
        assertEquals(SearchMode.AUTO, config.searchMode());
    }

    @Test
    void emptyInputYieldsDefaults() {
        assertEquals(PeciaConfig.defaults(), parser.parse(""));
    }

    @Test
    void defaultTemplateMatchesCodedDefaults() {
        String template = new PeciaConfigFile().defaultToml();

        assertEquals(PeciaConfig.defaults(), parser.parse(template));
    }

    @Test
    void malformedTomlIsRejected() {
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> parser.parse("[index\ninclude = ["));

        assertTrue(e.getMessage().contains("Invalid .pecia.toml"));
    }

    @Test
    void wrongValueTypeIsRejected() {
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> parser.parse("[chunk]\nmax_tokens = \"lots\""));

        assertTrue(e.getMessage().contains("chunk.max_tokens must be an integer"));
    }

    @ParameterizedTest
    @EnumSource(SearchMode.class)
    void parsesEverySearchMode(SearchMode mode) {
        String toml = "[search]\nmode = \"" + mode.configValue() + "\"\n";

        assertEquals(mode, parser.parse(toml).searchMode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"true\"", "1"})
    void semanticEnabledMustBeABoolean(String value) {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class, () -> parser.parse("[semantic]\nenabled = " + value + "\n"));

        assertEquals("semantic.enabled must be a boolean", e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "true", "[\"auto\"]"})
    void searchModeMustBeAString(String value) {
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> parser.parse("[search]\nmode = " + value + "\n"));

        assertEquals("search.mode must be a string", e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Hybrid", " auto", "", "fuzzy"})
    void unknownSearchModeNamesTheValidModes(String value) {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class, () -> parser.parse("[search]\nmode = \"" + value + "\"\n"));

        assertEquals(
                "search.mode must be one of lexical, semantic, hybrid, auto, got \"" + value + "\"", e.getMessage());
    }

    @ParameterizedTest
    @CsvSource({
        "chunk.max_tokens, chunk.max_tokens = 3000000000",
        "index.max_file_bytes, index.max_file_bytes = -3000000000"
    })
    void integerOutsideIntRangeIsRejectedAsInvalid(String key, String toml) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> parser.parse(toml));

        assertTrue(e.getMessage().contains(key + " must be a 32-bit integer"), e.getMessage());
    }

    @Test
    void nonStringGlobIsRejected() {
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> parser.parse("[index]\ninclude = [1, 2]"));

        assertTrue(e.getMessage().contains("index.include must contain only strings"));
    }

    @Test
    void leadingByteOrderMarkIsIgnored() {
        assertEquals(128, parser.parse("\uFEFF[chunk]\nmax_tokens = 128\n").maxTokens());
    }

    @ParameterizedTest
    @ValueSource(strings = {"index", "chunk", "semantic", "embed", "search", "store"})
    void sectionWrittenAsAPlainValueIsRejected(String section) {
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> parser.parse(section + " = 5\n"));

        assertTrue(e.getMessage().contains(section + " must be a table"), e.getMessage());
    }

    @Test
    void invalidRangeIsRejected() {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class, () -> parser.parse("[chunk]\nmax_tokens = 10\noverlap_tokens = 10"));

        assertTrue(e.getMessage().contains("overlap_tokens"));
    }
}
