package dev.eyuppastirmaci.pecia.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeciaConfigTest {

    @Test
    void rejectsNonPositiveMaxTokens() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> config(0, 0, 2, ".pecia/index.db"));

        assertTrue(e.getMessage().contains("max_tokens"));
    }

    @Test
    void rejectsNonPositiveMaxFileBytes() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new PeciaConfig(List.of(), List.of(), 0, 256, 32, 2, ".pecia/index.db"));

        assertTrue(e.getMessage().contains("max_file_bytes"));
    }

    @Test
    void rejectsOverlapNotBelowMaxTokens() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> config(256, 256, 2, ".pecia/index.db"));

        assertTrue(e.getMessage().contains("overlap_tokens"));
    }

    @Test
    void rejectsConcurrencyBelowOne() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> config(256, 32, 0, ".pecia/index.db"));

        assertTrue(e.getMessage().contains("concurrency"));
    }

    @Test
    void rejectsBlankStorePath() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> config(256, 32, 2, "  "));

        assertTrue(e.getMessage().contains("store.path"));
    }

    private static PeciaConfig config(int maxTokens, int overlapTokens, int concurrency, String storePath) {
        return new PeciaConfig(List.of(), List.of(), 1024, maxTokens, overlapTokens, concurrency, storePath);
    }
}
