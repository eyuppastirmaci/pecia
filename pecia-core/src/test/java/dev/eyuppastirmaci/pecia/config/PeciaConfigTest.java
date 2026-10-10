package dev.eyuppastirmaci.pecia.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PeciaConfigTest {

    @Test
    void rejectsNonPositiveMaxTokens() {
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> config(0, 0, 2, ".pecia/index.db"));

        assertTrue(e.getMessage().contains("max_tokens"));
    }

    @Test
    void rejectsNonPositiveMaxFileBytes() {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> new PeciaConfig(List.of(), List.of(), 0, 256, 32, 2, ".pecia/index.db"));

        assertTrue(e.getMessage().contains("max_file_bytes"));
    }

    @Test
    void rejectsOverlapNotBelowMaxTokens() {
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> config(256, 256, 2, ".pecia/index.db"));

        assertTrue(e.getMessage().contains("overlap_tokens"));
    }

    @Test
    void rejectsConcurrencyBelowOne() {
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> config(256, 32, 0, ".pecia/index.db"));

        assertTrue(e.getMessage().contains("concurrency"));
    }

    @Test
    void rejectsBlankStorePath() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> config(256, 32, 2, "  "));

        assertTrue(e.getMessage().contains("store.path"));
    }

    @Test
    void rejectsStorePathContainingNul() {
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> config(256, 32, 2, "index\0.db"));

        assertTrue(e.getMessage().contains("store.path must not contain NUL"));
    }

    @ParameterizedTest
    @CsvSource({"256, 0", "256, 253", "3, 0", "34, 31"})
    void chunkBudgetWithinTheTokenizerLimitIsAccepted(int maxTokens, int overlapTokens) {
        PeciaConfig config = config(maxTokens, overlapTokens, 2, ".pecia/index.db");

        assertDoesNotThrow(() -> config.requireChunkBudget(256, 2));
    }

    @Test
    void chunkBudgetErrorsNameTheConfigurationKeys() {
        assertEquals(
                "chunk.max_tokens must be between 3 and 256, got 257"
                        + " (the tokenizer accepts 256 tokens including 2 special tokens)",
                chunkBudgetFailure(257, 0));
        assertEquals(
                "chunk.max_tokens must be between 3 and 256, got 2"
                        + " (the tokenizer accepts 256 tokens including 2 special tokens)",
                chunkBudgetFailure(2, 0));
        assertEquals(
                "chunk.overlap_tokens must be less than 32, got 32"
                        + " (chunk.max_tokens = 34 leaves 32 content tokens after 2 special tokens)",
                chunkBudgetFailure(34, 32));
    }

    private static String chunkBudgetFailure(int maxTokens, int overlapTokens) {
        PeciaConfig config = config(maxTokens, overlapTokens, 2, ".pecia/index.db");

        return assertThrows(IllegalArgumentException.class, () -> config.requireChunkBudget(256, 2))
                .getMessage();
    }

    private static PeciaConfig config(int maxTokens, int overlapTokens, int concurrency, String storePath) {
        return new PeciaConfig(List.of(), List.of(), 1024, maxTokens, overlapTokens, concurrency, storePath);
    }
}
