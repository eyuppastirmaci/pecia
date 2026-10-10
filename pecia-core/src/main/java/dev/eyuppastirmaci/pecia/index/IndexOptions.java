package dev.eyuppastirmaci.pecia.index;

import dev.eyuppastirmaci.pecia.config.PeciaConfig;

/**
 * Choices a caller makes for one indexing run. An explicit choice overrides {@code .pecia.toml}, which in turn
 * overrides the default.
 *
 * @param semanticEnabled whether the run builds embeddings for semantic search; null leaves it to {@code
 *     semantic.enabled}
 */
public record IndexOptions(Boolean semanticEnabled) {

    /** Returns options that leave every setting to the project configuration. */
    public static IndexOptions configured() {
        return new IndexOptions(null);
    }

    /**
     * Returns options that turn semantic indexing on or off for this run, whatever the configuration says.
     *
     * @param enabled whether the run builds embeddings for semantic search
     */
    public static IndexOptions semantic(boolean enabled) {
        return new IndexOptions(enabled);
    }

    /**
     * Returns whether the run builds embeddings: the explicit choice if there is one, otherwise the configuration's.
     */
    boolean resolveSemanticEnabled(PeciaConfig config) {
        return semanticEnabled != null ? semanticEnabled : config.semanticEnabled();
    }
}
