package dev.eyuppastirmaci.pecia.config;

import java.util.List;

/**
 * Validated settings for candidate discovery, chunking, embedding, and storage.
 *
 * @param include project-relative globs; an empty list accepts every candidate
 * @param exclude project-relative globs that take precedence over includes
 * @param storePath database path, resolved against the project root when relative
 */
public record PeciaConfig(
        List<String> include,
        List<String> exclude,
        int maxFileBytes,
        int maxTokens,
        int overlapTokens,
        int embedConcurrency,
        String storePath) {

    /** Copies filter lists and rejects invalid limits or a blank storage path or one containing NUL. */
    public PeciaConfig {
        include = List.copyOf(include);
        exclude = List.copyOf(exclude);

        if (maxFileBytes <= 0) {
            throw new IllegalArgumentException("index.max_file_bytes must be positive, got " + maxFileBytes);
        }

        if (maxTokens <= 0) {
            throw new IllegalArgumentException("chunk.max_tokens must be positive, got " + maxTokens);
        }

        if (overlapTokens < 0 || overlapTokens >= maxTokens) {
            throw new IllegalArgumentException(
                    "chunk.overlap_tokens must be between 0 and max_tokens, got " + overlapTokens);
        }

        if (embedConcurrency < 1) {
            throw new IllegalArgumentException("embed.concurrency must be at least 1, got " + embedConcurrency);
        }

        if (storePath == null || storePath.isBlank()) {
            throw new IllegalArgumentException("store.path must not be blank");
        }

        if (storePath.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("store.path must not contain NUL");
        }
    }

    /**
     * Checks the chunk settings against a tokenizer's input limit and names the configuration keys in errors. The
     * constructor cannot do this because the limit depends on the tokenizer that indexing uses.
     *
     * @param maxInputTokens the tokenizer's input limit, including special tokens
     * @param specialTokenCount the tokens the tokenizer adds to every input
     * @throws IllegalArgumentException if {@code chunk.max_tokens} leaves no content tokens or exceeds the input
     *     limit, or {@code chunk.overlap_tokens} is not below the content budget
     */
    public void requireChunkBudget(int maxInputTokens, int specialTokenCount) {
        if (maxTokens <= specialTokenCount || maxTokens > maxInputTokens) {
            throw new IllegalArgumentException("chunk.max_tokens must be between "
                    + (specialTokenCount + 1)
                    + " and "
                    + maxInputTokens
                    + ", got "
                    + maxTokens
                    + " (the tokenizer accepts "
                    + maxInputTokens
                    + " tokens including "
                    + specialTokenCount
                    + " special tokens)");
        }

        int contentBudget = maxTokens - specialTokenCount;

        if (overlapTokens >= contentBudget) {
            throw new IllegalArgumentException("chunk.overlap_tokens must be less than "
                    + contentBudget
                    + ", got "
                    + overlapTokens
                    + " (chunk.max_tokens = "
                    + maxTokens
                    + " leaves "
                    + contentBudget
                    + " content tokens after "
                    + specialTokenCount
                    + " special tokens)");
        }
    }

    /**
     * Returns the config used when no .pecia.toml overrides anything.
     *
     * @return the default configuration
     */
    public static PeciaConfig defaults() {
        return new PeciaConfig(
                DefaultFileRules.includes(),
                DefaultFileRules.excludes(),
                5 * 1024 * 1024,
                256,
                32,
                2,
                ".pecia/index.db");
    }
}
