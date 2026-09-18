package dev.eyuppastirmaci.pecia.chunking.internal;

import dev.eyuppastirmaci.pecia.tokenization.TokenCounter;

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
        var identity = tokenCounter.identity();

        if (maxTokens <= identity.specialTokenCount() || maxTokens > identity.maxInputTokens()) {
            throw new IllegalArgumentException("maxTokens must be greater than "
                    + identity.specialTokenCount()
                    + " and at most "
                    + identity.maxInputTokens()
                    + ", including special tokens");
        }

        int contentBudget = maxTokens - identity.specialTokenCount();

        if (overlapTokens < 0 || overlapTokens >= contentBudget) {
            throw new IllegalArgumentException("overlapTokens must be non-negative and less than " + contentBudget);
        }

        return contentBudget;
    }
}
