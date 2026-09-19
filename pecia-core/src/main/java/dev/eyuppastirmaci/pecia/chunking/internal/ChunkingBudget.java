package dev.eyuppastirmaci.pecia.chunking.internal;

import dev.eyuppastirmaci.pecia.tokenization.TokenCounter;
import dev.eyuppastirmaci.pecia.tokenization.TokenizerCompatibility;
import dev.eyuppastirmaci.pecia.tokenization.TokenizerIdentity;

/** Shared validation of chunking limits against a tokenizer's model input contract. */
public final class ChunkingBudget {

    private ChunkingBudget() {}

    /**
     * Validates the model input and overlap limits and returns the available content token budget.
     *
     * @param tokenCounter tokenizer whose identity defines the model limits
     * @param maxTokens maximum model input tokens including special tokens
     * @param overlapTokens maximum content tokens shared between adjacent chunks
     * @return the content token budget after reserving special tokens
     * @throws NullPointerException if the tokenizer or its identity is null
     * @throws IllegalArgumentException if the model input or overlap limits are invalid
     */
    public static int validate(TokenCounter tokenCounter, int maxTokens, int overlapTokens) {
        return validate(tokenCounter.identity(), maxTokens, overlapTokens);
    }

    /**
     * Validates chunk limits using identity metadata without constructing or invoking a tokenizer.
     *
     * @return the content token budget after reserving special tokens
     * @throws NullPointerException if identity is null
     * @throws IllegalArgumentException if the model input or overlap limits are invalid
     */
    public static int validate(TokenizerIdentity identity, int maxTokens, int overlapTokens) {
        return validateLimits(identity.maxInputTokens(), identity.specialTokenCount(), maxTokens, overlapTokens);
    }

    /**
     * Validates chunk limits using model-independent tokenizer behavior metadata.
     *
     * @return the content token budget after reserving special tokens
     * @throws NullPointerException if tokenizer is null
     * @throws IllegalArgumentException if the input or overlap limits are invalid
     */
    public static int validate(TokenizerCompatibility tokenizer, int maxTokens, int overlapTokens) {
        return validateLimits(tokenizer.maxInputTokens(), tokenizer.specialTokenCount(), maxTokens, overlapTokens);
    }

    private static int validateLimits(int maxInputTokens, int specialTokenCount, int maxTokens, int overlapTokens) {
        if (maxTokens <= specialTokenCount || maxTokens > maxInputTokens) {
            throw new IllegalArgumentException("maxTokens must be greater than "
                    + specialTokenCount
                    + " and at most "
                    + maxInputTokens
                    + ", including special tokens");
        }

        int contentBudget = maxTokens - specialTokenCount;

        if (overlapTokens < 0 || overlapTokens >= contentBudget) {
            throw new IllegalArgumentException("overlapTokens must be non-negative and less than " + contentBudget);
        }

        return contentBudget;
    }
}
