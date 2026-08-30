package dev.eyuppastirmaci.pecia.config;

import java.util.List;

public record PeciaConfig(
        List<String> include,
        List<String> exclude,
        int maxTokens,
        int overlapTokens,
        int embedConcurrency,
        String storePath
) {

    public PeciaConfig {
        include = List.copyOf(include);
        exclude = List.copyOf(exclude);

        if (maxTokens <= 0) {
            throw new IllegalArgumentException("chunk.max_tokens must be positive, got " + maxTokens);
        }

        if (overlapTokens < 0 || overlapTokens >= maxTokens) {
            throw new IllegalArgumentException("chunk.overlap_tokens must be between 0 and max_tokens, got " + overlapTokens);
        }

        if (embedConcurrency < 1) {
            throw new IllegalArgumentException("embed.concurrency must be at least 1, got " + embedConcurrency);
        }

        if (storePath == null || storePath.isBlank()) {
            throw new IllegalArgumentException("store.path must not be blank");
        }
    }

    /**
     * Returns the config used when no .pecia.toml overrides anything.
     *
     * @return the default configuration
     */
    public static PeciaConfig defaults() {
        return new PeciaConfig(
                List.of("**/*.md", "**/*.txt", "**/*.java", "**/*.kt", "**/*.py", "**/*.ts"),
                List.of("**/target/**", "**/node_modules/**"),
                256,
                32,
                2,
                ".pecia/index.db"
        );
    }
}
