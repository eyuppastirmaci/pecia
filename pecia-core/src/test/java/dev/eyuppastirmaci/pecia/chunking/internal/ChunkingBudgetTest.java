package dev.eyuppastirmaci.pecia.chunking.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.eyuppastirmaci.pecia.tokenization.TokenizerCompatibility;
import org.junit.jupiter.api.Test;

/** The content budget is what remains of the chunk limit after the tokenizer's special tokens. */
class ChunkingBudgetTest {

    private static final TokenizerCompatibility TOKENIZER =
            new TokenizerCompatibility("test-tokenizer", "b".repeat(64), 321, 512, 3);

    @Test
    void returnsTheChunkLimitWithoutTheSpecialTokens() {
        assertEquals(253, ChunkingBudget.validate(TOKENIZER, 256, 32));
        assertEquals(509, ChunkingBudget.validate(TOKENIZER, 512, 0));
    }

    @Test
    void rejectsLimitsThatLeaveNoContentOrExceedTheModelInput() {
        assertThrows(IllegalArgumentException.class, () -> ChunkingBudget.validate(TOKENIZER, 3, 0));
        assertThrows(IllegalArgumentException.class, () -> ChunkingBudget.validate(TOKENIZER, 513, 0));
        assertThrows(IllegalArgumentException.class, () -> ChunkingBudget.validate(TOKENIZER, 256, 253));
    }
}
