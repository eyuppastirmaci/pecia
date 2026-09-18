package dev.eyuppastirmaci.pecia.index;

import dev.eyuppastirmaci.pecia.chunking.internal.ChunkingBudget;
import dev.eyuppastirmaci.pecia.config.PeciaConfig;
import dev.eyuppastirmaci.pecia.tokenization.TokenizerIdentity;

/**
 * The tokenizer compatibility key and token limits used to produce a file's chunks.
 *
 * @param maxTokens total chunk budget including model special tokens
 * @param overlapTokens maximum shared content tokens between adjacent chunks
 */
public record IndexingProfile(String tokenizerKey, int maxTokens, int overlapTokens) {

    /**
     * Validates stored metadata; {@link #from(PeciaConfig, TokenizerIdentity)} also checks model limits.
     */
    public IndexingProfile {
        if (tokenizerKey.isBlank()) {
            throw new IllegalArgumentException("tokenizerKey must not be blank");
        }

        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive: " + maxTokens);
        }

        if (overlapTokens < 0 || overlapTokens >= maxTokens) {
            throw new IllegalArgumentException("overlapTokens must be non-negative and less than maxTokens");
        }
    }

    /**
     * Creates a profile from chunk settings validated against the tokenizer's input contract.
     * Discovery, file admission, storage, and embedding settings do not change chunk compatibility.
     *
     * @throws IllegalArgumentException if the chunk budget is invalid for the tokenizer
     * @throws NullPointerException if config or tokenizerIdentity is null
     */
    public static IndexingProfile from(PeciaConfig config, TokenizerIdentity tokenizerIdentity) {
        int maxTokens = config.maxTokens();
        int overlapTokens = config.overlapTokens();
        ChunkingBudget.validate(tokenizerIdentity, maxTokens, overlapTokens);

        return new IndexingProfile(tokenizerIdentity.compatibilityKey(), maxTokens, overlapTokens);
    }
}
