package dev.eyuppastirmaci.pecia.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

                [embed]
                concurrency = 4

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
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("[index\ninclude = ["));

        assertTrue(e.getMessage().contains("Invalid .pecia.toml"));
    }

    @Test
    void wrongValueTypeIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("[chunk]\nmax_tokens = \"lots\""));

        assertTrue(e.getMessage().contains("chunk.max_tokens must be an integer"));
    }

    @Test
    void nonStringGlobIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("[index]\ninclude = [1, 2]"));

        assertTrue(e.getMessage().contains("index.include must contain only strings"));
    }

    @Test
    void invalidRangeIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("[chunk]\nmax_tokens = 10\noverlap_tokens = 10"));

        assertTrue(e.getMessage().contains("overlap_tokens"));
    }
}
